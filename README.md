# HR Roster Sync

Companies keep their employee list in an HR system and send changes to MoveInSync, which plans employee cab transport: new joiners, office transfers, shift changes and exits. Those changes arrive one by one through an API during the day, and as a full file of up to 200,000 employees every night. In practice the same change can arrive twice, changes can arrive in the wrong order, some records contain bad data, and the nightly file is sometimes cut short. This service keeps MoveInSync's employee records correct anyway, and it refuses to act on a nightly file that looks broken until a person has checked it.

## Contents

1. [At a glance](#at-a-glance)
2. [The problem in plain words](#the-problem-in-plain-words)
3. [How it works — the big picture](#how-it-works--the-big-picture)
4. [The workflows, step by step](#the-workflows-step-by-step)
5. [The rules, explained](#the-rules-explained)
6. [Design decisions](#design-decisions)
7. [Trade-offs: consistency vs availability vs latency](#trade-offs-consistency-vs-availability-vs-latency)
8. [Security and tenant isolation](#security-and-tenant-isolation)
9. [Failure handling](#failure-handling)
10. [Monitoring — what on-call sees at 3 AM](#monitoring--what-on-call-sees-at-3-am)
11. [Caching](#caching)
12. [Performance and cost](#performance-and-cost)
13. [Data model](#data-model)
14. [API reference](#api-reference)
15. [Run it](#run-it)
16. [Project structure](#project-structure)
17. [Testing strategy](#testing-strategy)
18. [Assumptions](#assumptions)
19. [Deliberately left out](#deliberately-left-out)
20. [Glossary](#glossary)

## At a glance

_Pending._

## The problem in plain words

_Pending._

## How it works — the big picture

_Pending._

## The workflows, step by step

_Pending._

## The rules, explained

_Pending._

## Design decisions

_Pending._

## Trade-offs: consistency vs availability vs latency

_Pending._

## Security and tenant isolation

_Pending._

## Failure handling

_Pending._

## Monitoring — what on-call sees at 3 AM

_Pending._

## Caching

_Pending._

## Performance and cost

_Pending._

## Data model

_Pending._

## API reference

_Pending._

## Run it

Prerequisites: JDK 21 and Docker. Maven is not required; the project ships with the Maven wrapper.

```bash
./mvnw test
```

## Project structure

_Pending._

## Testing strategy

_Pending._

## Assumptions

_Pending._

## Deliberately left out

_Pending._

## Glossary

_Pending._
