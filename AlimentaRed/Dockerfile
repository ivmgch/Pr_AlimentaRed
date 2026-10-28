# ============================================================================
# Dockerfile para AlimentaRed (Spring Boot 3.5.x / Java 21)
# Optimizado para despliegue en Render (Web Service)
# ============================================================================

# Fase 1: Compilación con Maven y Temurin JDK 21
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

# Copiar configuración de Maven y cachear dependencias
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw
RUN ./mvnw dependency:go-offline -B

# Copiar código fuente y compilar artefacto JAR ejecutable
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Fase 2: Runtime ligero con Temurin JRE 21
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Crear usuario sin privilegios para ejecución segura
RUN groupadd -r alimentared && useradd -r -g alimentared alimentared
USER alimentared:alimentared

# Copiar el binario compilado desde la fase de construcción
COPY --from=builder /workspace/target/*.jar /app/alimentared.jar

# Configuración de puerto por defecto (Render inyecta variable PORT)
ENV PORT=8080
EXPOSE 8080

# Parámetros optimizados para contenedor (Render inyecta PORT dinámico)
ENTRYPOINT ["sh", "-c", "exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom -Dserver.port=${PORT:-8080} -jar /app/alimentared.jar"]
