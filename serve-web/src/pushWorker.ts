// Entry point for `push-sw.js`, served at `/push-sw.js` and registered with scope `/` — but only
// when somebody turns notifications on in Settings (`push/settings.ts`). Two listeners and no
// `fetch` handler; the logic and the reasons are in `push/worker.ts`.

import {
    handleNotificationClick,
    handlePush,
    type WorkerScope,
} from "./push/worker.js";

interface ExtendableEventLike extends Event {
    waitUntil(promise: Promise<unknown>): void;
}

interface PushEventLike extends ExtendableEventLike {
    data: { text(): string } | null;
}

interface NotificationEventLike extends ExtendableEventLike {
    notification: Notification;
}

const scope = self as unknown as WorkerScope & {
    addEventListener(type: string, listener: (event: Event) => void): void;
    skipWaiting(): Promise<void>;
};

scope.addEventListener("install", (event) => {
    (event as ExtendableEventLike).waitUntil(scope.skipWaiting());
});

scope.addEventListener("push", (event) => {
    const push = event as PushEventLike;
    let text: string | null = null;
    try {
        text = push.data?.text() ?? null;
    } catch {
        text = null;
    }
    push.waitUntil(handlePush(scope, text));
});

scope.addEventListener("notificationclick", (event) => {
    const click = event as NotificationEventLike;
    click.waitUntil(handleNotificationClick(scope, click.notification));
});
