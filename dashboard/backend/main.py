import os
import logging
from pathlib import Path

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse

from kubernetes import client, config
from kubernetes.client.rest import ApiException

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("solstice-dashboard")

NAMESPACE = os.getenv("SOLSTICE_NAMESPACE", "solstice")
TELEMETRY_URL = os.getenv(
    "TELEMETRY_URL",
    "http://solstice-telemetry.solstice.svc.cluster.local:8080/telemetry/status",
)
CONTROLLER_NAME = os.getenv("CONTROLLER_NAME", "solstice-flink-controller")
FLINK_LABEL = os.getenv("FLINK_LABEL_SELECTOR", "app=solstice-flink")
STATIC_DIR = Path(os.getenv("STATIC_DIR", "/app/static"))

try:
    config.load_incluster_config()
    log.info("Loaded in-cluster kube config")
except config.ConfigException:
    config.load_kube_config()
    log.info("Loaded local kube config")

core = client.CoreV1Api()
apps = client.AppsV1Api()
custom = client.CustomObjectsApi()

app = FastAPI(title="solstice-dashboard")

_httpx = httpx.AsyncClient(timeout=5.0)


@app.get("/api/grid")
async def grid():
    try:
        r = await _httpx.get(TELEMETRY_URL)
        r.raise_for_status()
        return r.json()
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"telemetry unreachable: {e}")


@app.get("/api/cluster")
def cluster():
    jm_replicas = 0
    try:
        dep = apps.read_namespaced_deployment("solstice-flink", NAMESPACE)
        jm_replicas = dep.status.ready_replicas or 0
    except ApiException as e:
        log.warning("JM deployment read failed: %s", e.reason)

    tm_pods = 0
    try:
        pods = core.list_namespaced_pod(NAMESPACE, label_selector="component=taskmanager")
        tm_pods = sum(1 for p in pods.items if p.status.phase == "Running")
    except ApiException as e:
        log.warning("taskmanager pod list failed: %s", e.reason)

    return {
        "jobmanager_replicas": jm_replicas,
        "taskmanager_pods": tm_pods,
        "namespace": NAMESPACE,
    }


@app.get("/api/controller")
def controller():
    try:
        obj = custom.get_namespaced_custom_object(
            group="solstice.io",
            version="v1",
            namespace=NAMESPACE,
            plural="solsticecontrollers",
            name=CONTROLLER_NAME,
        )
        return {"spec": obj.get("spec", {}), "status": obj.get("status", {})}
    except ApiException as e:
        raise HTTPException(status_code=e.status, detail=e.reason)


@app.get("/healthz")
def healthz():
    return {"status": "ok"}


if STATIC_DIR.exists():
    app.mount("/assets", StaticFiles(directory=STATIC_DIR / "assets"), name="assets")

    @app.get("/")
    def index():
        return FileResponse(STATIC_DIR / "index.html")

    @app.get("/{path:path}")
    def spa_fallback(path: str):
        target = STATIC_DIR / path
        if target.is_file():
            return FileResponse(target)
        return FileResponse(STATIC_DIR / "index.html")
