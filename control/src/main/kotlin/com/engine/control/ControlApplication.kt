package com.engine.control

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * The exchange control plane.
 *
 * Reference data and shard topology are authored here, in Postgres, and *published* as the same
 * immutable `.properties` artifacts the engine, gateway, market-data and discovery processes have
 * always booted from. The database is deliberately not on their boot path: it can be down and the
 * cluster still starts, and two cluster nodes cannot read different geometry because a write landed
 * between their boots -- which is the one class of misconfiguration consensus cannot catch
 * (Design.md §7).
 */
@SpringBootApplication
@EnableScheduling
class ControlApplication

fun main(args: Array<String>) {
    runApplication<ControlApplication>(*args)
}
