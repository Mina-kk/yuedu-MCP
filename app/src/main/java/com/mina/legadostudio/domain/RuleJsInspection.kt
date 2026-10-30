package com.mina.legadostudio.domain

import org.htmlunit.corejs.javascript.CompilerEnvirons
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.Parser
import org.htmlunit.corejs.javascript.Token
import org.htmlunit.corejs.javascript.ast.Assignment
import org.htmlunit.corejs.javascript.ast.AstNode
import org.htmlunit.corejs.javascript.ast.CatchClause
import org.htmlunit.corejs.javascript.ast.FunctionNode
import org.htmlunit.corejs.javascript.ast.Name
import org.htmlunit.corejs.javascript.ast.ObjectProperty
import org.htmlunit.corejs.javascript.ast.PropertyGet
import org.htmlunit.corejs.javascript.ast.VariableInitializer

internal object RuleJsInspection {
    fun unresolvedReads(script: String, identifiers: Set<String>): Set<String> {
        val environment = CompilerEnvirons().apply { languageVersion = Context.VERSION_ES6 }
        val root = runCatching { Parser(environment).parse(script, "rule", 1) }.getOrNull() ?: return emptySet()
        val assignments = mutableListOf<Assignment>()
        val bindings = mutableListOf<Name>()
        val names = mutableListOf<Name>()
        root.visit { node ->
            if (node is Assignment && node.operator == Token.ASSIGN && node.left is Name) assignments += node
            if (node is VariableInitializer) node.target.visit { target ->
                if (target is Name && target.identifier in identifiers) bindings += target
                true
            }
            if (node is CatchClause) (node.varName as? Name)?.let { binding ->
                if (binding.identifier in identifiers) bindings += binding
            }
            if (node is Name && node.identifier in identifiers) names += node
            true
        }
        return names.filter { name ->
            val parent = name.parent
            val isProperty = parent is PropertyGet && parent.property === name ||
                parent is ObjectProperty && parent.key === name
            val isFunctionName = parent is FunctionNode && parent.functionName === name
            val isWrite = parent is Assignment && parent.operator == Token.ASSIGN && parent.left === name
            val hasDeclaration = name.enclosingScope?.getDefiningScope(name.identifier) != null ||
                bindings.any { binding -> binding.identifier == name.identifier &&
                    binding.enclosingFunction === name.enclosingFunction &&
                    (binding === name || binding.parent is CatchClause && isWithin(name, binding.parent) ||
                        binding.parent !is CatchClause && binding.absolutePosition <= name.absolutePosition) }
            val assignedEarlier = assignments.any { assignment ->
                (assignment.left as Name).identifier == name.identifier &&
                    assignment.absolutePosition + assignment.length <= name.absolutePosition &&
                    assignment.enclosingFunction === name.enclosingFunction &&
                    !isWithin(name, assignment)
            }
            !isProperty && !isFunctionName && !isWrite && !hasDeclaration && !assignedEarlier
        }.map { it.identifier }.toSet()
    }

    private fun isWithin(node: AstNode, ancestor: AstNode): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }
}
