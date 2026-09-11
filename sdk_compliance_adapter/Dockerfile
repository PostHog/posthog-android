FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app
COPY . .
RUN ./gradlew -Pcompliance :sdk_compliance_adapter:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/sdk_compliance_adapter/build/install/sdk_compliance_adapter /app
ARG SDK_PROFILE=core
ENV SDK_PROFILE=${SDK_PROFILE}
EXPOSE 8080
CMD ["/app/bin/sdk_compliance_adapter"]
