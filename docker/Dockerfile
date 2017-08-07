FROM openjdk:8-jre-alpine

ADD build/dist/nagaina*.tgz /opt/

RUN ln -s /opt/nagaina* /opt/nagaina

EXPOSE 9020

CMD ["java", "-jar", "/opt/nagaina/nagaina.jar"]

