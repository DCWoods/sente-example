FROM java:8

ADD target/sente-1.8.1-standalone.jar /srv/sente-example-app.jar

EXPOSE 8080

CMD ["java", "-jar", "/srv/sente-example-app.jar"]
