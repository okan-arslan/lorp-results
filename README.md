# LoRP: Code, Data and Corrected Results

Java branch-and-price code and benchmark inputs for the location-or-routing
problem, together with the corrected computational results.

- [Solver code](src/main/java/lorp/): branch-and-price, pulse pricing and CPLEX pricing.
- [Benchmark data](data/raw/) and [example configuration](data/example.properties).
- [Build and run instructions](docs/downloads/REPRODUCE.md).
- [Webpage and computational downloads](docs/index.html).
- [Result formats](docs/downloads/RESULTS.md) and [pricing description](docs/downloads/PRICING.md).

The solver needs a compatible JDK and a separately licensed IBM ILOG CPLEX
installation. The published computations used Java 17 and CPLEX 22.1.
No Python installation is needed to compile or run it. Users may modify the
Java source and instance parameters.

The webpage offers a self-contained
[solver-code-and-data ZIP](docs/downloads/lorp-code-and-data.zip), as well as the
CSV and the three computational-result ZIPs. The results cover 14,364
instances: 7,541 optimal within numerical tolerances, 2,904 infeasible and
3,919 not proven optimal.

The solver code and webpage were generated using ChatGPT and AI-assisted
tools, then reviewed and tested by Okan Arslan.

Software: [MIT](LICENSE). New results and text: [CC BY 4.0](LICENSE-DATA.md).
Benchmark rights are retained; see [third-party notices](THIRD-PARTY-NOTICES.md).
CPLEX binaries and licenses are not included.

For GitHub Pages, publish the `docs/` folder from the `main` branch using the
repository's Pages settings. The empty `docs/.nojekyll` file tells GitHub to
serve these static files directly, preserving the download URLs. No custom
build workflow is needed. See [GitHub's setup instructions](https://docs.github.com/en/pages/getting-started-with-github-pages/creating-a-github-pages-site).
