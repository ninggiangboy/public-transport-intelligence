plugins {
    id("pti.java-conventions")
    id("pti.jib-conventions")
}

description = "Flyway migrations and the db-migrate runner (ADR-0024)."

jib {
    to {
        image = jib.to.image!!.replace("/pti-db:", "/pti-db-migrate:")
    }
}
