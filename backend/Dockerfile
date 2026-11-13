# 后端镜像：Maven 多阶段构建，运行时仅需 JRE
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# 先拷 pom 利用依赖缓存层
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/rtbf-console.jar /app/rtbf-console.jar
RUN mkdir -p /app/evidence
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/rtbf-console.jar"]
