# syntax=docker/dockerfile:1.29

# Minimal standalone container for Livery public component publications (`/p/<slug>/r/<rev>/…`).
# Runs `livery-host` from yschimke/livery on a JRE with no Android SDK, Skiko, Robolectric, BTA,
# or `:server` runtime.
ARG LIVERY_VERSION=0.26.0

FROM eclipse-temurin:21.0.13.1_2-jre AS fetch
ARG LIVERY_VERSION
WORKDIR /opt
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors -o /tmp/livery.tgz \
      "https://github.com/yschimke/livery/releases/download/v${LIVERY_VERSION}/livery-host-${LIVERY_VERSION}.tar.gz" && \
    tar -xzf /tmp/livery.tgz -C /opt && \
    rm /tmp/livery.tgz && \
    ln -s "/opt/livery-host-${LIVERY_VERSION}" /opt/livery-host

FROM eclipse-temurin:21.0.13.1_2-jre
ARG LIVERY_VERSION
COPY --from=fetch "/opt/livery-host-${LIVERY_VERSION}" "/opt/livery-host-${LIVERY_VERSION}"
RUN ln -s "/opt/livery-host-${LIVERY_VERSION}" /opt/livery-host && \
    apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

ENV PATH="/opt/livery-host/bin:${PATH}" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

EXPOSE 8080
ENTRYPOINT ["livery-host"]
