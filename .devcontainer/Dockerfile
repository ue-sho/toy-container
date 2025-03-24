FROM mcr.microsoft.com/vscode/devcontainers/base:ubuntu-22.04

# Install essential packages
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends \
    build-essential \
    cmake \
    curl \
    git \
    openjdk-21-jdk \
    vim \
    && rm -rf /var/lib/apt/lists/*

# Install SDKMAN and Kotlin
USER vscode
RUN curl -s "https://get.sdkman.io" | bash
RUN bash -c "source /home/vscode/.sdkman/bin/sdkman-init.sh \
    && sdk install kotlin \
    && sdk install gradle"

# Set environment variables
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=$PATH:$JAVA_HOME/bin