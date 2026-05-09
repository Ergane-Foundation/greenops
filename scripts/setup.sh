#!/usr/bin/env bash
# GreenOps end-to-end bootstrap.
#
# Brings the whole system up on Minikube from a cold start:
#   1. Ensures Minikube is running (with MINIKUBE_HOME on the external SSD).
#   2. Builds all four local images into Minikube's docker daemon.
#   3. Installs/upgrades Flink Kubernetes Operator + MinIO via Helm.
#   4. Creates the MinIO bucket used by Flink for checkpoints/savepoints.
#   5. Deploys telemetry, the GreenOps CRD/RBAC/operator/CR, the FlinkDeployment,
#      and the dashboard.
#   6. Waits for every rollout to be Ready.
#   7. Runs the dirty-grid → clean-grid smoke test.
#
# Safe to re-run: every step is idempotent (kubectl apply / helm upgrade --install).
#
# Usage:   ./scripts/setup.sh
# Env:     MINIKUBE_HOME (defaults to /Volumes/SSD/dev/minikube)
#          SKIP_BUILD=1  → skip all docker builds
#          SKIP_TEST=1   → skip the dirty/clean smoke test at the end

set -euo pipefail


# Config
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

export MINIKUBE_HOME="${MINIKUBE_HOME:-/Volumes/SSD/dev/minikube}"
NS="greenops"
MINIO_USER="greenops"
MINIO_PASSWORD="greenops123"
MINIO_BUCKET="greenops"

log()  { printf "\033[1;32m[setup]\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33m[warn]\033[0m  %s\n" "$*"; }
die()  { printf "\033[1;31m[fail]\033[0m  %s\n" "$*" >&2; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "$1 not found in PATH"; }

for bin in minikube kubectl docker helm mvn; do require "$bin"; done


# 1. Minikube
log "MINIKUBE_HOME = $MINIKUBE_HOME"
if ! minikube status >/dev/null 2>&1; then
  log "Starting Minikube..."
  minikube start --driver=docker --cpus=4 --memory=8192
else
  log "Minikube already running."
fi

log "Pointing docker CLI at Minikube's daemon (eval minikube docker-env)"
eval "$(minikube docker-env)"


# 2. Images
if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
  warn "SKIP_BUILD=1 — skipping docker builds"
else
  log "Building flink-s3:1.19"
  docker build -f Dockerfile.flink -t flink-s3:1.19 .

  log "Building greenops/telemetry:latest"
  docker build -t greenops/telemetry:latest ./telemetry

  log "Building greenops/operator:latest (mvn package + docker build)"
  mvn -q -f operator/pom.xml clean package
  docker build -t greenops/operator:latest ./operator

  log "Building greenops/dashboard:latest"
  docker build -t greenops/dashboard:latest ./dashboard
fi


# 3. Namespace + Helm infra (Flink operator, MinIO)

log "Applying namespace"
kubectl apply -f k8s/namespace.yaml

# MinIO reads its root credentials from this Secret via auth.existingSecret, and
# the Flink pods read the same keys, so it has to exist before the Helm install.
log "Applying object store credentials Secret"
kubectl apply -f k8s/minio/object-store-secret.yaml

if ! helm repo list 2>/dev/null | grep -q '^flink-operator-repo'; then
  helm repo add flink-operator-repo https://downloads.apache.org/flink/flink-kubernetes-operator-1.8.0/
fi
if ! helm repo list 2>/dev/null | grep -q '^bitnami'; then
  helm repo add bitnami https://charts.bitnami.com/bitnami
fi
helm repo update >/dev/null

log "Installing/upgrading cert-manager (Flink operator dep)"
if ! kubectl get ns cert-manager >/dev/null 2>&1; then
  kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.4/cert-manager.yaml
  kubectl -n cert-manager wait --for=condition=Available deploy --all --timeout=180s
fi

log "Installing/upgrading flink-kubernetes-operator"
helm upgrade --install flink-kubernetes-operator \
  flink-operator-repo/flink-kubernetes-operator \
  --namespace "$NS" --values k8s/flink/flink-operator-values.yaml \
  --wait --timeout 5m

log "Installing/upgrading MinIO"
helm upgrade --install minio bitnami/minio \
  --namespace "$NS" --values k8s/minio/minio-values.yaml \
  --wait --timeout 5m


# 4. MinIO bucket
# The chart's defaultBuckets setting creates the bucket on first start. Flink
# cannot create it on demand, so verify rather than assume, and fall back to
# creating it by hand if the chart did not.
log "Verifying MinIO bucket '$MINIO_BUCKET' exists"
MINIO_POD="$(kubectl get pod -n "$NS" -l app.kubernetes.io/name=minio -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
if [[ -z "$MINIO_POD" ]]; then
  MINIO_POD="$(kubectl get pod -n "$NS" -l app=minio -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
fi

if [[ -z "$MINIO_POD" ]]; then
  warn "Could not locate a MinIO pod; skipping bucket verification"
else
  kubectl exec -n "$NS" "$MINIO_POD" -- \
    mc alias set local "http://localhost:9000" "$MINIO_USER" "$MINIO_PASSWORD" >/dev/null 2>&1 || true
  if kubectl exec -n "$NS" "$MINIO_POD" -- mc ls "local/$MINIO_BUCKET" >/dev/null 2>&1; then
    log "Bucket '$MINIO_BUCKET' present"
  else
    warn "Bucket '$MINIO_BUCKET' missing; creating it"
    kubectl exec -n "$NS" "$MINIO_POD" -- mc mb -p "local/$MINIO_BUCKET" >/dev/null 2>&1 || true
  fi
fi


# 5. Workloads
log "Applying FlinkDeployment"
kubectl apply -f k8s/flink/flink-cluster.yaml

log "Applying telemetry"
kubectl apply -f k8s/telemetry/telemetry-deployment.yaml

log "Applying GreenOps CRD + RBAC + operator"
kubectl apply -f k8s/greenops/greenops-crd.yaml
kubectl apply -f k8s/greenops/greenops-rbac.yaml
kubectl apply -f k8s/greenops/greenops-deployment.yaml

log "Applying dashboard"
kubectl apply -f k8s/dashboard/dashboard-deployment.yaml

log "Applying GreenOpsController CR"
kubectl apply -f k8s/greenops/greenops-cr.yaml

# Pick up new images if the deployments already existed
kubectl -n "$NS" rollout restart deployment/greenops-telemetry deployment/greenops-operator deployment/greenops-dashboard >/dev/null


# 6. Wait for rollouts
log "Waiting for rollouts..."
kubectl -n "$NS" rollout status deployment/greenops-telemetry --timeout=3m
kubectl -n "$NS" rollout status deployment/greenops-operator  --timeout=3m
kubectl -n "$NS" rollout status deployment/greenops-dashboard --timeout=3m
kubectl -n "$NS" rollout status deployment/greenops-flink     --timeout=5m

log "Cluster state:"
kubectl get pods -n "$NS"
kubectl get greenopscontrollers -n "$NS"


# 7. Smoke test: dirty → clean
if [[ "${SKIP_TEST:-0}" == "1" ]]; then
  warn "SKIP_TEST=1 — skipping dirty/clean smoke test"
else
  log "=== SMOKE TEST: DIRTY GRID ==="
  ./scripts/simulate-dirty-grid.sh
  log "Waiting ~90s for savepoint + scale-down..."
  sleep 90
  kubectl get greenopscontrollers -n "$NS"
  kubectl get deploy greenops-flink -n "$NS"

  log "=== SMOKE TEST: CLEAN GRID ==="
  ./scripts/simulate-clean-grid.sh
  log "Waiting ~60s for scale-up..."
  sleep 60
  kubectl get greenopscontrollers -n "$NS"
  kubectl get deploy greenops-flink -n "$NS"
fi


# Done
cat <<EOF

\033[1;32m✓ GreenOps is up.\033[0m

Useful next commands:
  # Follow operator logs
  kubectl logs -f deployment/greenops-operator -n $NS

  # Open the dashboard
  kubectl port-forward -n $NS svc/greenops-dashboard 8000:8000 &
  open http://localhost:8000

  # Open MinIO console (user=$MINIO_USER pass=$MINIO_PASSWORD)
  kubectl port-forward -n $NS svc/minio-console 9001:9001 &
  open http://localhost:9001

  # Toggle grid state manually
  ./scripts/simulate-dirty-grid.sh
  ./scripts/simulate-clean-grid.sh

  # Inspect savepoints in MinIO
  kubectl exec -n $NS \$(kubectl get pod -n $NS -l app=minio -o name | head -1) -- \\
    mc ls --recursive local/$MINIO_BUCKET/savepoints/
EOF
