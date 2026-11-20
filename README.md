# cartwall

[![CI](https://github.com/rkaushik-dev/cartwall/actions/workflows/ci.yml/badge.svg)](https://github.com/rkaushik-dev/cartwall/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Maven](https://img.shields.io/badge/build-Maven-C71A36)
![JUnit 5](https://img.shields.io/badge/tests-JUnit%205-25A162)

Traffic, continuity and billing for a small market radio group.

Six signals, one automation system, one sales staff and one traffic director.
The job is to turn a signed order into spots sitting in real breaks on a real
day, hand that day to the automation, take back what the automation says it
actually played, and bill from that rather than from what was scheduled.

Everything is dated on the broadcast calendar, which is not the wall calendar.
Start at `io.github.rkaushikdev.cartwall.BroadcastCalendar`.

## Tech stack

- Java 21, built with Maven (wrapper included)
- Spring Boot 3.5 (Web MVC, Validation) for the HTTP service, with springdoc for the
  OpenAPI document
- JPA (Hibernate) over H2, schema managed by Flyway migrations
- picocli for the command line
- JUnit 5, AssertJ, Spring's MockMvc; JaCoCo coverage and Spotless
  (palantir-java-format) enforced by the build

## Build, test, run

You need a JDK 21 on the path. Everything else comes through the wrapper.

```sh
./mvnw -B verify                      # format check, all tests, coverage gate
./mvnw -B spotless:apply              # format the sources
./mvnw -B test -Dtest=PlacerTest      # one test class
```

`verify` leaves an executable jar in `target/`:

```sh
java -jar target/cartwall-0.1.0.jar --help
java -jar target/cartwall-0.1.0.jar init-db --yes
CARTWALL_API_KEYS='tronan:traffic:secret' java -jar target/cartwall-0.1.0.jar serve --port 8000
```

### Configuration

Settings come from the environment, and `--database` on the command line beats it.

| variable | default | what |
| --- | --- | --- |
| `CARTWALL_DATABASE_URL` | `jdbc:h2:file:./cartwall;AUTO_SERVER=TRUE` | JDBC URL of the database |
| `CARTWALL_MARKET` | `Waterloo Cedar Falls` | the house, printed on paper |
| `CARTWALL_HORIZON_DAYS` | `14` | how many days ahead the log builder runs |
| `CARTWALL_ECHO_SQL` | `no` | log the SQL Hibernate sends |
| `CARTWALL_STRICT_COPY` | `yes` | refuse a cart nobody has dubbed |
| `CARTWALL_LOG_LEVEL` | `info` | level of the package's own event log |
| `CARTWALL_API_KEYS` | empty | `name:role:key` entries, `;` separated; roles are `reader`, `seller`, `traffic`, `manager` |

With no keys configured the service lets nobody in, rather than everybody.

## Project layout

```
src/main/java/io/github/rkaushikdev/cartwall/
  (root)        money, air time, the broadcast calendar, settings, errors, the event log
  domain/       stations, dayparts, clocks, rate cards, accounts, orders, separation, political
  scheduling/   inventory boards, the placer, pinning, bumping, the weekly run
  logs/         the day log, approval
  playout/      the automation's log export and as-run files
  reconcile/    matching the as-run to the log, posting it back
  billing/      the invoice document
  reports/      affidavits, statements, sellout, avails, the political file
  services/     the jobs: log build, invoicing, crediting, makegoods, numbering, audit
  persistence/  entities, repositories, the unit of work, Flyway-backed database
  api/          the HTTP service, its key gate and error shape; routes in api/routers
  cli/          the command line
src/main/resources/db/migration/   Flyway migrations, V1 to V7
src/test/java/                     one test class per module under test
```

## Command line

```
cartwall [-d URL] <command>
```

Everything prints to standard output, and nothing to standard error unless it
failed. The exit code is the answer: `0` it worked, `1` a refusal the operator can
act on (printed as `code: message`), `2` the arguments were wrong. Destructive
commands ask first unless given `--yes`.

| command | what |
| --- | --- |
| `init-db [--yes]` | build the schema in an empty database |
| `drop-db [--yes]` | take the schema out, and everything in it |
| `status` | what the process is pointed at, and whether the database answers |
| `stations` | the signals on file, and which of them get a log |
| `build DAY` | rebuild the broadcast week DAY falls in, across the cluster |
| `build-export STATION DAY [--into DIR]` | write the log in the automation's format |
| `post-asrun STATION DAY FILE [--force]` | read the automation's as-run and post it |
| `check STATION DAY` | whether the log can be signed, and what is wrong |
| `approve STATION DAY --who NAME` | sign the log |
| `release STATION DAY` | send the log to the automation |
| `invoice MONTH [--on DAY] [--dry-run]` | raise the invoices for a broadcast month (`2025-08`) |
| `credit INVOICE AMOUNT --reason R [--note] [--on] [--spots]` | write one credit |
| `owing [--limit N]` | invoices with something still on them, most owing first |
| `serve [--host H] [--port P]` | run the HTTP service until it is stopped |

Days are written `YYYY-MM-DD`.

## HTTP endpoints

Every route but health and readiness needs an `X-Cartwall-Key` header, and each
needs a role at least as high as the one shown. Money crosses the wire as a string
in dollars (`"60.00"`), never as a JSON number. Every failure has one shape:
`{"code": ..., "message": ..., "context": {...}}`. The OpenAPI document is at
`/openapi.json` and a browsable form at `/docs`.

| method | path | role | what |
| --- | --- | --- | --- |
| GET | `/health` | none | is the process up |
| GET | `/ready` | none | is the database there |
| POST | `/orders` | seller | write an order |
| GET | `/orders?advertiser=CODE` | reader | one advertiser's orders |
| GET | `/orders/{number}` | reader | read an order |
| PUT | `/orders/{number}` | seller | rewrite a pending order |
| POST | `/orders/{number}/confirm` | seller | make it schedulable |
| POST | `/orders/{number}/cancel` | seller | take it out of the run |
| DELETE | `/orders/{number}` | seller | delete a pending order |
| POST | `/logs/build/{week_of}` | traffic | rebuild a broadcast week |
| GET | `/logs/{station}/{day}?spots=` | reader | a log in air order, or its spots only |
| GET | `/logs/{station}/{day}/ready` | reader | problems and warnings before signing |
| POST | `/logs/{station}/{day}/approve` | traffic | sign it (`{"who": ..., "at": ...}`) |
| POST | `/logs/{station}/{day}/release` | traffic | send it out |
| POST | `/logs/{station}/{day}/revise` | manager | open a released log back up |
| GET | `/logs/{station}?first=&last=` | reader | the state of each day in a span |
| GET | `/avails/{station}/{day}` | reader | what is open, by daypart (the rows overlap) |
| GET | `/avails/{station}/{day}/hours?thinnest=` | reader | what is open, hour by hour |
| GET | `/avails/{station}/week/{week_of}` | reader | a broadcast week, one row a day |
| GET | `/accounts` | reader | the agencies |
| POST | `/accounts` | manager | open an advertiser |
| GET | `/accounts/{code}` | reader | an advertiser, its codes, commission and payer |
| GET | `/accounts/{code}/conflicts` | reader | who it may not sit beside, and through which codes |
| PUT | `/accounts/{code}/credit?standing=` | manager | open, cash in advance, or hold |
| POST | `/accounts/copy` | traffic | file a dubbed cut |
| GET | `/accounts/{code}/copy` | reader | an advertiser's copy library |
| POST | `/reconcile/{station}/{day}/check` | traffic | compare an as-run (CSV body) with the log; writes nothing |
| POST | `/reconcile/{station}/{day}?force=` | traffic | compare and post the result onto the log |
| POST | `/reconcile/{station}/{day}/aired` | manager | say the day went out |
| GET | `/reconcile/order/{order}?first=&last=&aired=` | reader | what one order aired, or missed, in a span |
