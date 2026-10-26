# Build and Run the LoRP Solver

## Obtain the Code and Data

Download `lorp-code-and-data.zip` from the webpage and extract it, or obtain
the complete repository. The ZIP contains the Java solver, benchmark inputs,
an example configuration, these instructions as `README.md`, and licenses.
Work from the extracted `lorp-code-and-data` directory or the repository root.

## Requirements

Use a compatible JDK and a separately licensed IBM ILOG CPLEX installation.
The published computations used Java 17 and CPLEX 22.1. CPLEX's Java library
`cplex.jar` and native libraries must be installed locally; they are not included.
Python is not required.

## Compile

The following commands assume Linux and a bash-compatible terminal. Adjust
the paths to your CPLEX installation, especially its native-library directory
on other platforms.

```sh
export CPLEX_HOME=/path/to/CPLEX_Studio221
export CPLEX_JAR="$CPLEX_HOME/cplex/lib/cplex.jar"
export CPLEX_NATIVE_DIR="$CPLEX_HOME/cplex/bin/x86-64_linux"
mkdir -p build/classes
javac --release 11 -cp "$CPLEX_JAR" -d build/classes $(find src/main/java -name '*.java')
```

## Run an Instance

```sh
java -Xmx8g -XX:ActiveProcessorCount=1 \
  -Djava.library.path="$CPLEX_NATIVE_DIR" \
  -cp "build/classes:$CPLEX_JAR" \
  lorp.RunMain production data/example.properties runs/example-1/result.xml
```

The solver compiles directly from Java source; there is no separate installer.
In a Java IDE, add `cplex.jar` as a library, set the native-library path, and
run `lorp.RunMain` with the same three arguments shown above. Keep the working
directory at the repository or extracted ZIP root.

Use a new output directory for each run. The program writes `result.xml`, a
three-hour `snapshot.xml`, and periodic progress XML files. These Java Properties
XML documents contain the status, bounds, runtime, search statistics and any
incumbent solution. A run can finish before its limit.

The published JSON and CSV downloads describe the completed computational
study. A new run writes XML; it does not update those published downloads.

## Change the Instance or Parameters

Edit `data/example.properties` or make another configuration file. The example
uses `akca/r30x5a-1`, with `R=0`, `T=100`, unrestricted facility count,
uncapacitated facilities, and weights `wF=wR=1`, `wA=0`.

| Property | Meaning |
| --- | --- |
| `benchmark.path` | Benchmark file path relative to the configuration file. |
| `capacitated` | `true` enforces facility capacities; `false` does not. Vehicle capacity remains enforced. |
| `p` | Maximum open-facility count; `-1` means unrestricted. |
| `R` | Direct-coverage radius. |
| `T` | Maximum route length. |
| `wF`, `wR`, `wA` | Facility, routing and allocation objective weights. |
| `pricing` | `pulse` or `milp`, both exact pricing methods. |
| `heuristics` | Whether to use the optional heuristics alongside exact search. |
| `time_limit_seconds` | `18000` for the production interface. |
| `snapshot_seconds` | `10800` for the production interface. |

The webpage's `instances.csv` supplies the network and parameter values for
every reported instance. In that CSV, an empty `p` corresponds to `p=-1` in
the configuration, and `True`/`False` becomes lowercase `true`/`false`.
To use a different benchmark with a configuration in `data/`, set
`benchmark.path=raw/<network>` using its path below `data/raw/`.

## Distance and Cost Conventions

Akca inputs use unrounded Euclidean arcs. Prodhon and expanded inputs use
ceiling Euclidean arcs. Distances are scaled by `100 / maximum raw arc distance`;
demands, capacities and fixed costs are not scaled. Legacy vehicle-fixed and
demand-cost header fields are excluded from the LoRP objective.

Keep these conventions when comparing with the released results. The Java
source and input parameters may be modified under the accompanying licenses;
changed models or inputs should be identified as different experiments.
