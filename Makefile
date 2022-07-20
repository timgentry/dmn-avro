compile:
	mvn compile

run:
	mvn -q exec:java -Dexec.mainClass=example.App
