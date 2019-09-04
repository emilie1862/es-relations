
FROM openjdk:8-jre

WORKDIR /opt/regp

CMD ["java","-jar","service.jar"]

EXPOSE 8080

COPY . ./

RUN set -ex && ln -s *.[jw]ar service.jar

LABEL gov.geoplatform.regp=registry \
 gov.geoplatform.regp.registry.rest.location=http://:8080