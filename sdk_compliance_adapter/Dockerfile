FROM --platform=linux/amd64 gradle:8.5-jdk17 AS builder

WORKDIR /app

COPY . .

RUN gradle :sdk_compliance_adapter:build --no-daemon

FROM eclipse-temurin:11-jre

WORKDIR /app

# Copy the built JAR
COPY --from=builder /app/sdk_compliance_adapter/build/libs/*.jar /app/adapter.jar

# Expose port
EXPOSE 8080

# Run the adapter
CMD ["java", "-jar", "/app/adapter.jar"]
