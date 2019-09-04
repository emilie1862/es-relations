
FROM openjdk:8-jre

COPY * ./

RUN ln -s *.[jw]ar service.jar

EXPOSE 8080

CMD ["java","-jar","service.jar"]