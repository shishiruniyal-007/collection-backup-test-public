.PHONY: build test clean

build:
	javac RateLimiter.java

test: build
	javac RateLimiterTest.java
	java RateLimiterTest

clean:
	rm -f *.class
