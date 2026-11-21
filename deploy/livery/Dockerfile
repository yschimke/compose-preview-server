# syntax=docker/dockerfile:1.29

# Minimal standalone container for Livery public component publications (`/p/<slug>/r/<rev>/…`).
# Runs `:livery-host` on a JRE with no Android SDK, Skiko, Robolectric, BTA, or `:server` runtime.
ARG SERVER_VERSION

FROM eclipse-temurin:21.0.13_9-jre AS fetch
ARG SERVER_VERSION
WORKDIR /opt
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors -o /tmp/livery.tgz \
      "https://github.com/yschimke/compose-preview-server/releases/download/v${SERVER_VERSION}/compose-preview-livery-host-${SERVER_VERSION}.tar.gz" && \
    tar -xzf /tmp/livery.tgz -C /opt && \
    rm /tmp/livery.tgz && \
    ln -s "/opt/compose-preview-livery-host-${SERVER_VERSION}" /opt/compose-preview-livery-host

FROM eclipse-temurin:21.0.13_9-jre
ARG SERVER_VERSION
COPY --from=fetch "/opt/compose-preview-livery-host-${SERVER_VERSION}" "/opt/compose-preview-livery-host-${SERVER_VERSION}"
RUN ln -s "/opt/compose-preview-livery-host-${SERVER_VERSION}" /opt/compose-preview-livery-host && \
    apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

ENV PATH="/opt/compose-preview-livery-host/bin:${PATH}" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

EXPOSE 8080
ENTRYPOINT ["compose-preview-livery-host"]
