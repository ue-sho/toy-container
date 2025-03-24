# ToyContainer

A simple container runtime implemented in Kotlin that demonstrates the basic concepts of containerization.

## Features

- **Process Isolation**: Uses Linux namespaces (PID, UTS, Mount) to isolate processes
- **Filesystem Isolation**: Provides a separate root filesystem for containers
- **Resource Limitations**: Implements cgroup restrictions for memory and CPU usage
- **Image Management**: Supports building, listing, and removing container images
- **Simple CLI**: Easy-to-use command-line interface

## Building

```bash
./gradlew build
```

## Usage

### Running a Container

```bash
sudo ./gradlew run --args="run <image>:<tag> <command> [args...]"
```

Example:
```bash
sudo ./gradlew run --args="run test-image:1.0 /bin/sh"
```

### Building an Image

```bash
sudo ./gradlew run --args="build <directory> <name>:<tag>"
```

Example:
```bash
sudo ./gradlew run --args="build /path/to/rootfs myimage:1.0"
```

### Listing Images

```bash
sudo ./gradlew run --args="images"
```

### Removing an Image

```bash
sudo ./gradlew run --args="rmi <name>:<tag>"
```
