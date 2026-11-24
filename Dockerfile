FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY IndieVault.java indievault.html ./
RUN javac IndieVault.java
EXPOSE 8080
CMD ["java", "IndieVault"]