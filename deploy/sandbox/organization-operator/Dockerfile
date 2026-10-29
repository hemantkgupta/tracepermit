# Build only with the one human-selected cached digest-pinned Java21 image and accepted archive.
# This recipe does not pull, push, install packages, mount private input or deploy a workload.
FROM eclipse-temurin@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d
ARG ORGANIZATION_SHA256
ARG SOURCE_COMMIT
USER 0:0
COPY organization.tar /tmp/organization.tar
RUN case "$ORGANIZATION_SHA256$SOURCE_COMMIT" in ""|*[!0-9a-f]*) exit 1 ;; esac \
    && test "${#ORGANIZATION_SHA256}" -eq 64 \
    && test "${#SOURCE_COMMIT}" -eq 40 \
    && printf '%s  %s\n' "$ORGANIZATION_SHA256" /tmp/organization.tar | sha256sum -c - \
    && mkdir -p /opt/organization \
    && tar -xf /tmp/organization.tar --strip-components=1 -C /opt/organization \
    && test -x /opt/organization/bin/tracepermit-synthetic-organization-operator \
    && test -x /opt/java/openjdk/bin/java \
    && rm /tmp/organization.tar \
    && chown -R root:root /opt/organization \
    && chmod -R a+rX,a-w /opt/organization
LABEL io.tracepermit.source-commit=$SOURCE_COMMIT
LABEL io.tracepermit.organization-archive-sha256=$ORGANIZATION_SHA256
USER 10001:10001
WORKDIR /opt/organization
ENTRYPOINT ["/opt/organization/bin/tracepermit-synthetic-organization-operator"]
