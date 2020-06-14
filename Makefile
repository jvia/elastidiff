VERSION=$(shell git describe --always)
SOURCES=$(shell find src -type f -name '*.clj')

ok:
	@echo $(SOURCES)

clean:
	rm -rf classes target elastidiff *.zip

.PHONY: test
test:
	clojure -A:test:runner

elasticsearch/start:
	docker-compose -f dev/docker-compose.yml up --detach elasticsearch

elasticsearch/stop:
	docker-compose -f dev/docker-compose.yml down

elasticsearch/index:
	clojure -A:test -e "((requiring-resolve,'user/index))"

elasticsearch/test:
	clojure -m io.gamayun.elastidiff "http://localhost:9200/src" "http://localhost:9200/dst" | colordiff


######################################################################
# Build
######################################################################

docker/build-image:
	docker build -t elastidiff-graal:linux -f dev/Dockerfile.graalvm .

install: target/elastidiff-macos-amd64
	cp target/elastidiff-macos-amd64 /usr/local/bin/elastidiff

build: target/elastidiff-macos-amd64.zip target/elastidiff-linux-amd64.zip
	@echo Artifacts in target/

# mac
target/elastidiff-macos-amd64: $(SOURCES)
	mkdir -p target
	clojure -A:native-image
	mv elastidiff target/elastidiff-macos-amd64

target/elastidiff-macos-amd64.zip: target/elastidiff-macos-amd64
	zip target/elastidiff-macos-amd64.zip target/elastidiff-macos-amd64
	sha256sum target/elastidiff-macos-amd64.zip > target/elastidiff-macos-amd64.sha256

# linux
target/elastidiff-linux-amd64: $(SOURCES)
	mkdir -p target
	docker run --rm -it --volume $(CURDIR):/app  elastidiff-graal:linux clojure -A:native-image
	mv elastidiff target/elastidiff-linux-amd64

target/elastidiff-linux-amd64.zip: target/elastidiff-linux-amd64
	zip target/elastidiff-linux-amd64.zip target/elastidiff-linux-amd64
	sha256sum target/elastidiff-linux-amd64.zip > target/elastidiff-linux-amd64.sha256
