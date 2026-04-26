FROM openjdk:17-slim
WORKDIR /app
COPY VoiceServer.java .
RUN javac VoiceServer.java
EXPOSE 8080
CMD ["java", "VoiceServer"]
