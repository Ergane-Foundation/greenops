PROFILE ?= solstice
CPUS ?= max
MEMORY ?= max

export MINIKUBE_PROFILE := $(PROFILE)
export MINIKUBE_CPUS := $(CPUS)
export MINIKUBE_MEMORY := $(MEMORY)

.PHONY: help up deploy demo demo-dirty demo-clean status test teardown context

help:
	@echo "make up          create the cluster, build and deploy everything, run the smoke test"
	@echo "make deploy      the same without the smoke test"
	@echo "make demo        suspend the job, then resume it from its savepoint"
	@echo "make demo-dirty  suspend the job and wait for its savepoint"
	@echo "make demo-clean  resume the job and wait for it to restore"
	@echo "make status      show the controller, the Flink job and the pods"
	@echo "make test        run the operator test suite"
	@echo "make teardown    delete the cluster"
	@echo ""
	@echo "PROFILE=$(PROFILE) CPUS=$(CPUS) MEMORY=$(MEMORY)"

up:
	./scripts/setup.sh

deploy:
	SKIP_TEST=1 ./scripts/setup.sh

demo: demo-dirty demo-clean

demo-dirty: context
	./scripts/demo.sh dirty

demo-clean: context
	./scripts/demo.sh clean

status: context
	kubectl get solsticecontrollers,flinkdeployments -n solstice
	kubectl get pods -n solstice

test:
	mvn -f operator/pom.xml verify

teardown:
	minikube delete -p $(PROFILE)

context:
	@kubectl config use-context $(PROFILE) >/dev/null
