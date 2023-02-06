FROM amazoncorretto:17.0.4

RUN mkdir /app
COPY build/libs/kotlinbook-1.0-SNAPSHOT-all.jar /app/
CMD exec java -jar /app/kotlinbook-1.0-SNAPSHOT-all.jar