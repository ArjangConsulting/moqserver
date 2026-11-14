.PHONY: build test smoke e2e run clean release lint-server format-server docker-build docker-run studio-build studio-package studio-run studio-test studio-lint studio-dmg studio-deb studio-msi studio-uber-jar studio-bootstrap install-hooks

# ── Server (Swift) ──────────────────────────────────────────────

build:
	cd server && swift build

test:
	cd server && swift test

smoke:
	cd server && swift test --filter SmokeTests

e2e:
	cd server && swift test --filter MoqIntegrationTests

run:
	cd server && swift run moqserver serve --project Tests/MoqFormatTests/Fixtures/sample-app.moqproj --port 8080

clean:
	cd server && swift package clean
	cd studio && ./gradlew clean

release:
	cd server && swift build -c release --product moqserver

lint-server:
	cd server && swift-format lint --strict --recursive --configuration .swift-format Sources Tests Package.swift

format-server:
	cd server && swift-format format -i --recursive --configuration .swift-format Sources Tests Package.swift

docker-build:
	docker build -t moqserver ./server

docker-run:
	cd server && docker compose up

# ── Studio (Compose Multiplatform) ──────────────────────────────

ifeq ($(shell uname),Darwin)
override JAVA_HOME := $(shell /usr/libexec/java_home -v 21 2>/dev/null)
export JAVA_HOME
endif

# Fetches the unpublished kmp-code-editor source on a fresh clone; a no-op with a sibling checkout.
studio-bootstrap:
	bash studio/scripts/fetch-code-editor.sh

studio-build: studio-bootstrap
	cd studio && ./gradlew :composeApp:compileKotlinDesktop

studio-run: studio-bootstrap
	cd studio && ./gradlew :composeApp:run

studio-package: studio-bootstrap
	cd studio && ./gradlew :composeApp:packageDistributionForCurrentOS

studio-dmg: studio-bootstrap
	cd studio && ./gradlew :composeApp:packageDmg

studio-deb: studio-bootstrap
	cd studio && ./gradlew :composeApp:packageDeb

studio-msi: studio-bootstrap
	cd studio && ./gradlew :composeApp:packageMsi

studio-uber-jar: studio-bootstrap
	cd studio && ./gradlew :composeApp:packageUberJarForCurrentOS

studio-test: studio-bootstrap
	cd studio && ./gradlew test

studio-lint: studio-bootstrap
	cd studio && ./gradlew detektAll

# ── Dev Setup ───────────────────────────────────────────────────

install-hooks:
	cp scripts/pre-commit .git/hooks/pre-commit
	chmod +x .git/hooks/pre-commit
	@echo "Git hooks installed."
