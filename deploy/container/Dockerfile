FROM eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0

ARG TRACEPERMIT_SOURCE_COMMIT
ARG TRACEPERMIT_ARCHIVE_SHA256
ARG TRACEPERMIT_PACKAGE_VERSION=0.1.0

LABEL org.opencontainers.image.title="TracePermit simulator approval" \
      org.opencontainers.image.description="Source-free, simulator-only TracePermit approval runtime" \
      org.opencontainers.image.version="${TRACEPERMIT_PACKAGE_VERSION}" \
      org.opencontainers.image.revision="${TRACEPERMIT_SOURCE_COMMIT}" \
      org.opencontainers.image.source="https://github.com/hemantkgupta/tracepermit" \
      io.tracepermit.profile="simulator-approval-container-foundation" \
      io.tracepermit.distribution.sha256="${TRACEPERMIT_ARCHIVE_SHA256}"

RUN groupadd --gid 10001 tracepermit \
    && useradd --uid 10001 --gid 10001 --no-create-home \
      --home-dir /nonexistent --shell /usr/sbin/nologin tracepermit \
    && install -d -o root -g root -m 0755 /opt/tracepermit /run/tracepermit

COPY distribution.tar.gz /tmp/distribution.tar.gz
RUN tar -xzf /tmp/distribution.tar.gz --strip-components=1 -C /opt/tracepermit \
    && rm /tmp/distribution.tar.gz \
    && chown -R root:root /opt/tracepermit \
    && chmod -R a-w /opt/tracepermit

COPY tracepermit-container-prepare /usr/local/bin/tracepermit-container-prepare
COPY tracepermit-container-healthcheck /usr/local/bin/tracepermit-container-healthcheck
RUN chown root:root /usr/local/bin/tracepermit-container-prepare \
      /usr/local/bin/tracepermit-container-healthcheck \
    && chmod 0555 /usr/local/bin/tracepermit-container-prepare \
      /usr/local/bin/tracepermit-container-healthcheck

USER 10001:10001
WORKDIR /opt/tracepermit
ENV TRACEPERMIT_APPROVAL_CONFIG=/run/tracepermit/operator.json

ENTRYPOINT ["/opt/tracepermit/bin/tracepermit-simulator-approval"]
CMD ["--check"]
