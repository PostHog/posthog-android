FROM --platform=linux/amd64 gradle:8.5-jdk17 AS builder

WORKDIR /app

COPY . .

RUN echo 'include(":sdk_compliance_adapter")' >> settings.gradle.kts && \
    sed -i '/^dependencyResolutionManagement/i plugins {\n    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"\n}' settings.gradle.kts && \
    mkdir -p /root/.gradle && \
    echo 'org.gradle.java.installations.auto-download=true' > /root/.gradle/gradle.properties && \
    gradle :sdk_compliance_adapter:installDist --no-daemon

FROM eclipse-temurin:11-jre

WORKDIR /app

COPY --from=builder /app/sdk_compliance_adapter/build/install/sdk_compliance_adapter /app

EXPOSE 8080

CMD ["/app/bin/sdk_compliance_adapter"]
