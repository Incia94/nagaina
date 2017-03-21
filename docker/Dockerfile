FROM java:8

MAINTAINER EMC

ADD build/dist/nagaina*.tgz /opt/

RUN ln -s /opt/nagaina* /opt/nagaina

CMD ["java", "-jar", "/opt/nagaina/nagaina.jar"]
