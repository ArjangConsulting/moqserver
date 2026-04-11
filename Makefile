.PHONY: build test smoke run clean release docker-build studio-build studio-package studio-run studio-test studio-lint install-hooks

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
	cd server && swift run moqserver serve --spec ../samples/server/openapi.yaml --port 8080

clean:
	cd server && swift package clean
	cd studio && ./gradlew clean

release:
	cd server && swift build -c release

docker-build:
	docker build -t moqserver ./server

docker-run:
	cd server && docker-compose up

# ── Studio (Compose Multiplatform) ──────────────────────────────

override JAVA_HOME := $(shell /usr/libexec/java_home -v 21 2>/dev/null)
export JAVA_HOME

studio-build:
	cd studio && ./gradlew :composeApp:compileKotlinDesktop

studio-run:
	cd studio && ./gradlew :composeApp:run

studio-package:
	cd studio && ./gradlew :composeApp:packageDistributionForCurrentOS

studio-dmg:
	cd studio && ./gradlew :composeApp:packageDmg

studio-deb:
	cd studio && ./gradlew :composeApp:packageDeb

studio-msi:
	cd studio && ./gradlew :composeApp:packageMsi

studio-uber-jar:
	cd studio && ./gradlew :composeApp:packageUberJarForCurrentOS

studio-test:
	cd studio && ./gradlew test

studio-lint:
	cd studio && ./gradlew detektAll

# ── Dev Setup ───────────────────────────────────────────────────

install-hooks:
	cp scripts/pre-commit .git/hooks/pre-commit
	chmod +x .git/hooks/pre-commit
	@echo "Git hooks installed."
