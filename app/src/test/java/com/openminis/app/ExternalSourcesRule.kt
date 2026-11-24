package com.openminis.app

import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class ExternalSourcesRule(private val present: Boolean) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            Assume.assumeTrue("external monorepo sources are not part of this checkout", present)
            base.evaluate()
        }
    }
}
