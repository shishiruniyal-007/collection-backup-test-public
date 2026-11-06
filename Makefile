.PHONY: build test clean

# Compile all sources in one pass so both classes are always in sync.
build:
	javac RateLimiter.java RateLimiterTest.java

test: build
	java RateLimiterTest

clean:
	rm -f *.class
