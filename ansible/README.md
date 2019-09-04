Deploy new version of app to AWS from project directory:

`gradle/w installDist --info`

`cd ansible`

`pipenv install`

`pipenv run ansible-playbook -i ./hosts playbook.yml`

Add the -v flag for verbose logging

The private key to access the AWS server should be at ~/.ssh/HeritageTrail_AWS.pem