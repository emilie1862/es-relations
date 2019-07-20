Start up the Elasticsearch and Kibana containers:
```
docker-compose up -d
```

Stop the Elasticsearch and Kibana containers:
```
docker-compose down
```

Start Service
```
gradle/w run --info
```

Debug: add `--debug-jvm` flag


Example Searches:
`localhost:8081?q=Leesburg`
`localhost:8081?relationship.relatedPlace=Leesburg`
