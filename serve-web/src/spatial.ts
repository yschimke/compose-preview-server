import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import {
    parseSpatialScene,
    spatialTextureUrl,
    type SpatialSceneDocument,
} from "./spatial/model";

const METRES_PER_DP = 0.001;

class ComposeSpatialView extends HTMLElement {
    private renderer?: THREE.WebGLRenderer;
    private camera?: THREE.PerspectiveCamera;
    private controls?: OrbitControls;
    private world?: THREE.Group;
    private source?: SpatialSceneDocument;
    private resizeObserver?: ResizeObserver;
    private session?: XRSession;
    private status?: HTMLElement;
    private enterButton?: HTMLButtonElement;
    private disposables: Array<{ dispose(): void }> = [];

    connectedCallback(): void {
        this.renderShell();
        void this.load();
    }

    disconnectedCallback(): void {
        this.resizeObserver?.disconnect();
        void this.session?.end();
        this.controls?.dispose();
        for (const item of this.disposables) item.dispose();
        this.disposables = [];
        this.renderer?.setAnimationLoop(null);
        this.renderer?.dispose();
    }

    private renderShell(): void {
        const root = this.attachShadow({ mode: "open" });
        root.innerHTML = `
            <style>
                :host { display:block; position:relative; width:100%; min-height:560px;
                    overflow:hidden; border-radius:8px; background:#15171c; color:#fff; }
                canvas { display:block; width:100%; height:100%; min-height:560px; touch-action:none; }
                .bar { position:absolute; z-index:2; top:12px; left:12px; right:12px; display:flex;
                    align-items:center; gap:8px; pointer-events:none; }
                .title, .status { padding:7px 10px; border:1px solid #ffffff2b; border-radius:999px;
                    background:#17191ee6; backdrop-filter:blur(10px); font:600 12px/1.2 system-ui; }
                .status { margin-left:auto; font-weight:500; color:#d7d9df; }
                button { pointer-events:auto; border:1px solid #ffffff38; border-radius:999px;
                    padding:8px 12px; color:#fff; background:#5b5ce2; font:700 12px system-ui;
                    cursor:pointer; }
                button.secondary { background:#17191ee6; }
                button:disabled { opacity:.55; cursor:default; }
                .error { position:absolute; inset:0; display:grid; place-content:center; padding:32px;
                    text-align:center; font:600 14px/1.5 system-ui; background:#15171c; }
            </style>
            <div class="bar">
                <span class="title">Spatial preview</span>
                <button class="secondary" id="recenter" type="button">Recenter</button>
                <span class="status" role="status">Loading scene…</span>
                <button id="enter" type="button" disabled>Enter VR</button>
            </div>
        `;
        this.status = root.querySelector<HTMLElement>(".status") ?? undefined;
        this.enterButton =
            root.querySelector<HTMLButtonElement>("#enter") ?? undefined;
        root.querySelector("#recenter")?.addEventListener("click", () =>
            this.recenter(),
        );
        this.enterButton?.addEventListener(
            "click",
            () => void this.toggleSession(),
        );
    }

    private async load(): Promise<void> {
        try {
            const sceneUrl = this.getAttribute("scene-url");
            if (!sceneUrl) throw new Error("No spatial scene URL was provided");
            const response = await fetch(sceneUrl, {
                credentials: "same-origin",
            });
            if (!response.ok)
                throw new Error(`Scene request failed (${response.status})`);
            this.source = parseSpatialScene(await response.json());
            await this.createRenderer(sceneUrl, this.source);
            await this.detectXr();
            this.setStatus("Drag to orbit · scroll to zoom");
        } catch (error) {
            const message = document.createElement("div");
            message.className = "error";
            message.setAttribute("role", "alert");
            message.textContent =
                error instanceof Error
                    ? error.message
                    : "Could not open spatial preview";
            this.shadowRoot?.append(message);
        }
    }

    private async createRenderer(
        sceneUrl: string,
        source: SpatialSceneDocument,
    ): Promise<void> {
        const renderer = new THREE.WebGLRenderer({
            antialias: true,
            alpha: false,
        });
        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
        renderer.outputColorSpace = THREE.SRGBColorSpace;
        renderer.xr.enabled = true;
        renderer.xr.setReferenceSpaceType("local-floor");
        renderer.domElement.setAttribute(
            "aria-label",
            this.getAttribute("label") ?? "Interactive spatial preview",
        );
        this.shadowRoot?.append(renderer.domElement);

        const scene = new THREE.Scene();
        scene.background = this.environment(source);
        const world = new THREE.Group();
        scene.add(world);

        const textureLoader = new THREE.TextureLoader();
        for (const panel of [...source.panels, ...(source.orbiters ?? [])]) {
            const texture = await textureLoader.loadAsync(
                spatialTextureUrl(sceneUrl, panel.texture),
            );
            texture.colorSpace = THREE.SRGBColorSpace;
            const geometry = new THREE.PlaneGeometry(
                panel.sizeDp.width * METRES_PER_DP,
                panel.sizeDp.height * METRES_PER_DP,
            );
            const material = new THREE.MeshBasicMaterial({
                map: texture,
                transparent: true,
                side: THREE.DoubleSide,
            });
            this.disposables.push(texture, geometry, material);
            const mesh = new THREE.Mesh(geometry, material);
            const translation = panel.poseInRoot.translation;
            const rotation = panel.poseInRoot.rotation;
            mesh.position.set(
                translation.x * METRES_PER_DP,
                translation.y * METRES_PER_DP,
                translation.z * METRES_PER_DP,
            );
            mesh.quaternion
                .set(rotation.x, rotation.y, rotation.z, rotation.w)
                .normalize();
            mesh.name = panel.label ?? panel.id;
            world.add(mesh);
        }

        const cameraSpec = source.camera;
        const target = new THREE.Vector3(
            cameraSpec.target.x * METRES_PER_DP,
            cameraSpec.target.y * METRES_PER_DP,
            cameraSpec.target.z * METRES_PER_DP,
        );
        const yaw = THREE.MathUtils.degToRad(cameraSpec.yawDeg);
        const pitch = THREE.MathUtils.degToRad(cameraSpec.pitchDeg);
        const distance = cameraSpec.distance * METRES_PER_DP;
        const camera = new THREE.PerspectiveCamera(50, 1, 0.01, 100);
        camera.position.set(
            target.x + Math.cos(pitch) * Math.sin(yaw) * distance,
            target.y + Math.sin(pitch) * distance,
            target.z + Math.cos(pitch) * Math.cos(yaw) * distance,
        );
        const controls = new OrbitControls(camera, renderer.domElement);
        controls.target.copy(target);
        controls.enableDamping = true;
        controls.update();

        this.renderer = renderer;
        this.camera = camera;
        this.controls = controls;
        this.world = world;
        this.addControllerRays(scene, renderer);
        renderer.xr.addEventListener("sessionstart", () => {
            controls.enabled = false;
            this.setStatus("VR session active");
            window.setTimeout(() => this.recenter(), 100);
        });
        renderer.xr.addEventListener("sessionend", () => {
            controls.enabled = true;
            world.position.set(0, 0, 0);
            this.session = undefined;
            if (this.enterButton) this.enterButton.textContent = "Enter VR";
            this.setStatus("Drag to orbit · scroll to zoom");
        });
        renderer.setAnimationLoop(() => {
            controls.update();
            renderer.render(scene, camera);
        });
        this.resizeObserver = new ResizeObserver(() => this.resize());
        this.resizeObserver.observe(this);
        this.resize();
    }

    private environment(source: SpatialSceneDocument): THREE.Color {
        const explicit = source.environment?.color;
        if (
            source.environment?.kind === "color" &&
            /^#[0-9a-f]{6}$/i.test(explicit ?? "")
        ) {
            return new THREE.Color(explicit);
        }
        return new THREE.Color(
            source.environment?.preset === "studio-dark"
                ? "#111827"
                : "#3a3540",
        );
    }

    private addControllerRays(
        scene: THREE.Scene,
        renderer: THREE.WebGLRenderer,
    ): void {
        const geometry = new THREE.BufferGeometry().setFromPoints([
            new THREE.Vector3(0, 0, 0),
            new THREE.Vector3(0, 0, -2),
        ]);
        for (let index = 0; index < 2; index += 1) {
            const controller = renderer.xr.getController(index);
            const rayGeometry = geometry.clone();
            const rayMaterial = new THREE.LineBasicMaterial({
                color: 0xa7c7ff,
            });
            this.disposables.push(rayGeometry, rayMaterial);
            controller.add(new THREE.Line(rayGeometry, rayMaterial));
            scene.add(controller);
        }
        geometry.dispose();
    }

    private async detectXr(): Promise<void> {
        if (!this.enterButton) return;
        if (!window.isSecureContext) {
            this.enterButton.textContent = "HTTPS required for VR";
            return;
        }
        let supported = false;
        try {
            supported =
                (await navigator.xr?.isSessionSupported("immersive-vr")) ??
                false;
        } catch {
            supported = false;
        }
        this.enterButton.disabled = !supported;
        this.enterButton.textContent = supported
            ? "Enter VR"
            : "VR not available";
    }

    private async toggleSession(): Promise<void> {
        if (this.session) {
            await this.session.end();
            return;
        }
        if (!navigator.xr || !this.renderer) return;
        try {
            const session = await navigator.xr.requestSession("immersive-vr", {
                optionalFeatures: ["local-floor", "bounded-floor"],
            });
            this.session = session;
            if (this.enterButton) this.enterButton.textContent = "Exit VR";
            await this.renderer.xr.setSession(session);
        } catch (error) {
            this.setStatus(
                error instanceof Error ? error.message : "Could not enter VR",
            );
        }
    }

    private recenter(): void {
        if (!this.world || !this.camera || !this.source) return;
        if (!this.renderer?.xr.isPresenting) {
            this.controls?.reset();
            return;
        }
        const head = this.renderer.xr.getCamera().position;
        const spec = this.source.camera;
        const yaw = THREE.MathUtils.degToRad(spec.yawDeg);
        const pitch = THREE.MathUtils.degToRad(spec.pitchDeg);
        const eye = new THREE.Vector3(
            (spec.target.x + Math.cos(pitch) * Math.sin(yaw) * spec.distance) *
                METRES_PER_DP,
            (spec.target.y + Math.sin(pitch) * spec.distance) * METRES_PER_DP,
            (spec.target.z + Math.cos(pitch) * Math.cos(yaw) * spec.distance) *
                METRES_PER_DP,
        );
        this.world.position.copy(head).sub(eye);
    }

    private resize(): void {
        if (!this.renderer || !this.camera) return;
        const width = Math.max(this.clientWidth, 1);
        const height = Math.max(this.clientHeight, 560);
        this.renderer.setSize(width, height, false);
        this.camera.aspect = width / height;
        this.camera.updateProjectionMatrix();
    }

    private setStatus(message: string): void {
        if (this.status) this.status.textContent = message;
    }
}

if (!customElements.get("cp-spatial-view")) {
    customElements.define("cp-spatial-view", ComposeSpatialView);
}
