# Stage 1: Build the application
FROM gradle:8.14-jdk21 AS builder

WORKDIR /app

# Copy gradle files first for better caching
COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# Download dependencies (cached unless gradle files change)
RUN gradle dependencies --no-daemon

# Copy source code
COPY src ./src

# Build the fat jar
RUN gradle shadowJar --no-daemon

# Stage 2: Runtime with distroless
FROM gcr.io/distroless/java21-debian12:nonroot

WORKDIR /app

# Copy the fat jar from builder
COPY --from=builder /app/build/libs/*-all.jar app.jar

# Expose the default port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
