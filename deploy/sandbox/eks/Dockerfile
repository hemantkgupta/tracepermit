# Build only with human-selected cached digest-pinned Java21 image and archives.
# This file does not build/pull/push an image or deploy a workload on its own.
ARG JAVA21_IMAGE
FROM ${JAVA21_IMAGE}
ARG JAVA21_IMAGE
ARG LIFECYCLE_SHA256
ARG BUNDLE_SHA256
ARG SOURCE_COMMIT
USER 0:0
COPY lifecycle.tar /tmp/lifecycle.tar
COPY sandbox.tar /tmp/sandbox.tar
RUN case "$JAVA21_IMAGE" in *@sha256:*) ;; *) exit 1 ;; esac \
    && task_base_digest="${JAVA21_IMAGE##*@sha256:}" \
    && case "$task_base_digest" in ""|*[!0-9a-f]*) exit 1 ;; esac \
    && test "${#task_base_digest}" -eq 64 \
    && case "$LIFECYCLE_SHA256$BUNDLE_SHA256$SOURCE_COMMIT" in ""|*[!0-9a-f]*) exit 1 ;; esac \
    && test "${#LIFECYCLE_SHA256}" -eq 64 \
    && test "${#BUNDLE_SHA256}" -eq 64 \
    && test "${#SOURCE_COMMIT}" -eq 40 \
    && printf '%s  %s\n' "$LIFECYCLE_SHA256" /tmp/lifecycle.tar "$BUNDLE_SHA256" /tmp/sandbox.tar | sha256sum -c - \
    && mkdir -p /opt/tracepermit /opt/sandbox \
    && tar -xf /tmp/lifecycle.tar --strip-components=1 -C /opt/tracepermit \
    && tar -xf /tmp/sandbox.tar --strip-components=1 -C /opt/sandbox \
    && test -x /opt/tracepermit/bin/tracepermit-synthetic-operation-lifecycle \
    && test -x /opt/java/openjdk/bin/java \
    && rm /tmp/lifecycle.tar /tmp/sandbox.tar \
    && chown -R root:root /opt/tracepermit /opt/sandbox \
    && chmod -R a+rX,a-w /opt/tracepermit /opt/sandbox
LABEL io.tracepermit.source-commit=$SOURCE_COMMIT
USER 10001:10001
WORKDIR /opt/sandbox
ENTRYPOINT ["/opt/sandbox/bin/tracepermit-eks-sandbox"]
