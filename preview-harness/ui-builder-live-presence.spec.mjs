import { expect, test } from "@playwright/test";

const operatorToken = process.env.SERVE_TOKEN;
test.skip(!process.env.SERVE_URL || !operatorToken, "needs a real token-gated UI-builder server");

async function mintEditorGrant(request, label) {
    const opened = await request.post("/agent-access/request", {
        data: {
            scope: "live",
            label,
            ttlSeconds: 1800,
            capabilities: ["ui-builder-read", "ui-builder-write"],
        },
    });
    expect(opened.ok()).toBeTruthy();
    const grantRequest = await opened.json();
    const approval = await request.get(
        `/agent-access/${grantRequest.requestId}?token=${encodeURIComponent(operatorToken)}`,
    );
    const html = await approval.text();
    const csrf = html.match(/name="csrf" value="([^"]+)"/)?.[1];
    expect(csrf).toBeTruthy();
    const form = new URLSearchParams([
        ["action", "approve"],
        ["csrf", csrf],
        ["scope", "live"],
        ["ttl", "1800"],
        ["capability", "ui-builder-read"],
        ["capability", "ui-builder-write"],
    ]);
    const approved = await request.post(
        `/agent-access/${grantRequest.requestId}?token=${encodeURIComponent(operatorToken)}`,
        {
            data: form.toString(),
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
        },
    );
    expect(approved.ok()).toBeTruthy();
    const polled = await request.post("/agent-access/poll", {
        data: {
            requestId: grantRequest.requestId,
            deviceSecret: grantRequest.deviceSecret,
        },
    });
    const credential = await polled.json();
    expect(credential.status).toBe("approved");
    const whoami = await request.get("/agent-access/whoami", {
        headers: { "X-Compose-Preview-Token": credential.token },
    });
    const identity = await whoami.json();
    return { token: credential.token, actorId: `agent:${identity.fingerprint}` };
}

async function grantDesignAccess(request, designId, actorIds) {
    const response = await request.post(
        `/api/ui-builder/v1/requests?token=${encodeURIComponent(operatorToken)}`,
        {
            data: {
                schemaVersion: 1,
                requestId: "presence-harness-access",
                actorId: "operator",
                request: {
                    type: "updateDesignAccess",
                    designId,
                    baseAccessRevision: 0,
                    mutations: actorIds.map((actorId) => ({
                        type: "grantActor",
                        actorId,
                        role: "editor",
                        allowedActions: ["read", "write"],
                    })),
                },
            },
        },
    );
    expect(response.ok(), await response.text()).toBeTruthy();
}

function liveUrl(designId, actor, token, clientId, displayName, color, create = false) {
    const query = new URLSearchParams({
        session: "live",
        designId,
        actor,
        token,
        clientId,
        displayName,
        color,
    });
    if (create) query.set("create", "1");
    return `/ui-builder/?${query}`;
}

test("two authenticated browser actors exchange real ephemeral selection presence", async ({
    browser,
    request,
}, testInfo) => {
    const designId = `presence-${Date.now()}`;
    const firstGrant = await mintEditorGrant(request, "Presence browser Ada");
    const secondGrant = await mintEditorGrant(request, "Presence browser Lin");

    const owner = await browser.newContext();
    const ownerPage = await owner.newPage();
    await ownerPage.goto(
        liveUrl(designId, "operator", operatorToken, "owner-seed", "Owner", "#FF777777", true),
    );
    await expect
        .poll(() => ownerPage.locator("html").getAttribute("data-ui-builder-ready"), {
            timeout: 60_000,
        })
        .toBe("true");
    await grantDesignAccess(request, designId, [firstGrant.actorId, secondGrant.actorId]);

    const first = await browser.newContext();
    const second = await browser.newContext();
    const firstPage = await first.newPage();
    const secondPage = await second.newPage();
    const pageErrors = [];
    firstPage.on("pageerror", (error) => pageErrors.push(error.message));
    secondPage.on("pageerror", (error) => pageErrors.push(error.message));
    await Promise.all([
        firstPage.goto(
            liveUrl(
                designId,
                firstGrant.actorId,
                firstGrant.token,
                "ada-browser",
                "Ada",
                "#FFEF5350",
            ),
        ),
        secondPage.goto(
            liveUrl(
                designId,
                secondGrant.actorId,
                secondGrant.token,
                "lin-browser",
                "Lin",
                "#FF42A5F5",
            ),
        ),
    ]);
    await Promise.all(
        [firstPage, secondPage].map((page) =>
            expect
                .poll(() => page.locator("html").getAttribute("data-ui-builder-ready"), {
                    timeout: 60_000,
                })
                .toBe("true"),
        ),
    );
    await expect
        .poll(() => firstPage.evaluate(() => globalThis.__uiBuilderPresence?.actorIds || []), {
            timeout: 30_000,
        })
        .toContain(secondGrant.actorId);
    await expect
        .poll(() => secondPage.evaluate(() => globalThis.__uiBuilderPresence?.actorIds || []), {
            timeout: 30_000,
        })
        .toContain(firstGrant.actorId);
    expect(
        await firstPage.evaluate(() => globalThis.__uiBuilderPresence.selections.flat().length),
    ).toBeGreaterThan(0);
    expect(
        await secondPage.evaluate(() => globalThis.__uiBuilderPresence.selections.flat().length),
    ).toBeGreaterThan(0);
    expect(pageErrors).toEqual([]);

    // Compose exposes the action through its accessibility overlay while the Skia canvas remains
    // the top hit-test surface. Force the semantic action instead of relying on DOM pointer order.
    await firstPage.getByRole("button", { name: /^Reconnect/ }).click({ force: true });
    await expect
        .poll(() => firstPage.evaluate(() => globalThis.__uiBuilderPresence?.actorIds || []), {
            timeout: 30_000,
        })
        .toContain(secondGrant.actorId);
    expect(pageErrors).toEqual([]);

    await testInfo.attach("ada-observes-lin", {
        body: await firstPage.screenshot(),
        contentType: "image/png",
    });
    await testInfo.attach("lin-observes-ada", {
        body: await secondPage.screenshot(),
        contentType: "image/png",
    });
    await Promise.all([first.close(), second.close(), owner.close()]);
});
