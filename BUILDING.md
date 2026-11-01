# Building this fork of Archiva (3.0.2)

This fork depends on release versions of several sibling projects that are **not published to Maven Central**.
They have to be built and installed into your local Maven repository (`~/.m2`) first, in the order below.

| # | Repository | Version | Ref |
|---|------------|---------|-----|
| 1 | [archiva-parent](https://github.com/ilopezch/archiva-parent) | 19 | tag `v19` |
| 2 | [archiva-components](https://github.com/ilopezch/archiva-components) | 3.1 | tag `v3.1` |
| 3 | [archiva-redback-components](https://github.com/ilopezch/archiva-redback-components) | 1.0 | tag `v1.0` |
| 4 | [archiva-redback-core](https://github.com/ilopezch/archiva-redback-core) | 3.0.1 | tag `v3.0.1` |
| 5 | [archiva](https://github.com/ilopezch/archiva) (this repository) | 3.0.2 | `master` |

## Requirements

- JDK 11 or newer (`maven.compiler.release` is 11; the build was done with JDK 17)
- Maven 3.8 or newer (the build was done with 3.9.9)
- `export MAVEN_OPTS="-Xmx768m -Xms768m"` to avoid out-of-memory errors

## Steps

Clone the repositories **side by side** in the same directory. Several poms reference their parent with
`<relativePath>../archiva-parent/pom.xml</relativePath>`.

```bash
mkdir archiva-build && cd archiva-build
git clone -b v19   https://github.com/ilopezch/archiva-parent.git
git clone -b v3.1   https://github.com/ilopezch/archiva-components.git
git clone -b v1.0   https://github.com/ilopezch/archiva-redback-components.git
git clone -b v3.0.1 https://github.com/ilopezch/archiva-redback-core.git
git clone https://github.com/ilopezch/archiva.git

for p in archiva-parent archiva-components archiva-redback-components archiva-redback-core; do
  (cd $p && mvn clean install -DskipTests) || break
done

cd archiva
mvn clean install -DskipTests
```

Each step must succeed before the next one starts, because each project resolves the previous ones from `~/.m2`.

## Result

- Web application: `archiva-modules/archiva-web/archiva-webapp/target/archiva-webapp-3.0.2.war`
- Standalone distribution: `archiva-jetty/target/apache-archiva-3.0.2-bin.tar.gz` (and `.zip`)

## Notes

- Run the tests by leaving out `-DskipTests`; see `CLAUDE.md` for running a single test class.
- When upgrading a deployment, remove the old unpacked webapp directory before deploying the new war.
  The JavaScript files are served with `Cache-Control: no-cache` (`NoCacheFilter`, mapped to `/js/*`), so browsers
  revalidate them after an upgrade.
- Why these versions: the upstream projects only exist as `-SNAPSHOT` versions. The poms of this fork and of the
  sibling projects above were changed to fixed release versions so that the build is reproducible.
