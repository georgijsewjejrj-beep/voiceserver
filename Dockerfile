FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY VoiceServer.java .
RUN javac VoiceServer.java
EXPOSE 8080
CMD ["java", "VoiceServer"]
