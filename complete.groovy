#!/usr/bin/env groovy

import groovy.transform.Canonical
import org.codehaus.groovy.control.CompilerConfiguration

class Dsl {
    GroovyShell shell
    String script

    Dsl(String script) {
        this.script = script
        def config = new CompilerConfiguration(scriptBaseClass: 'CommandDsl')
        shell = new GroovyShell(this.class.classLoader, new Binding(), config)
    }

    Command evaluate() {
        shell.evaluate(script)
    }
}

@Canonical
class Command {
    String name
    String parentName
    String description
    List<Flag> flags = []
    List<Flag> persistentFlags = []
    List<Command> subcommands = []

    List<Flag> getAllFlags() {
        flags + persistentFlags
    }

    String toString() {
        prettPrint(0)
    }

    String prettPrint(int indentLevel) {
        def indent = '  ' * indentLevel
        def s = "${indent}Command($name${description ? ', ' + description : ''})"
        if (flags || persistentFlags) {
            s += "\n${indent}  ${printFlags(indentLevel + 1)}"
        }
        if (subcommands) {
            s += "\n${indent}  ${printSubcommands(indentLevel + 1)}"
        }
        s
    }

    String printFlags(int indentLevel) {
        def indent = '  ' * indentLevel
        (flags + persistentFlags)
                .sort { it.name }
                .collect { "${indent}--$it.name - $it.description" }
                .join("\n$indent")
    }

    String printSubcommands(int indentLevel) {
        def indent = '  ' * indentLevel
        subcommands
                .collect { it.prettPrint(indentLevel)}
                .join("\n$indent")
    }
}

@Canonical
class Flag {
    String name
    String abbreviation
    String description
}

abstract class CommandDsl extends Script {
    Command root
    Command parent
    Command command

    def run() {
        runScript()
        root
    }

    abstract runScript()

    void command(String name, Closure body) {
        command(name, null, body)
    }

    void command(String name, String description) {
        println "command($name)"
        command.subcommands << new Command(name: name, description: description, parentName: parent?.name)
    }

    void command(String name, String description, Closure body) {
        println "command($name) parent: ${parent?.name} command: ${command?.name}"
        if (command) {
            parent = command
        }

        command = new Command(name: name, description: description, parentName: parent?.name)

        if (parent) {
            parent.subcommands << command
            command.persistentFlags.addAll(parent.persistentFlags)
        }

        if (!root) {
            root = command
        }

        build(command, body)

        command = parent
    }

    void flag(String name, String description) {
        flag(name, null, description)
    }

    void flag(String name, String abbreviation, String description) {
        println "flag($name)"
        command.flags << new Flag(name, abbreviation, description)
    }

    void persistentFlag(String name, String description) {
        persistentFlag(name, null, description)
    }

    void persistentFlag(String name, String abbreviation, String description) {
        println "persistentFlag($name)"
        command.persistentFlags << new Flag(name, abbreviation, description)
    }

    void build(Command delegate, Closure body) {
        println "build($delegate.name)"
        def closure = body.clone()
        closure.delegate = delegate
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure()
    }
}

class CompletionRenderer {
    Command root

    CompletionRenderer(Command command) {
        root = command
    }

    String render() {
        render(root)
    }

    String render(Command command) {
        def s =  """|
                    |function _$command.name {
                    |""".stripMargin()

        if (command.subcommands) {
            s += """|  local -a commands
                    |""".stripMargin()
        }

        if (command.allFlags) {
            s += """|  _arguments -C \\
                    |    ${command.allFlags.collect { renderFlag(it) } .join(' \\\n    ')} \\
                    |    '1: :->cmds' \\
                    |    '*::arg:->args'
                    |""".stripMargin()
        }

        if (command.subcommands) {
            s += """|  case \$state in
                    |  cmds)
                    |    commands=(
                    |      ${command.subcommands.collect { "'$it.name:$it.description'" } .join('\n      ') }
                    |    )
                    |    _describe "command" commands
                    |    ;;
                    |  esac
                    |
                    |  case "\$words[1]" in
                    |${command.subcommands.collect { renderCaseCommand(it) } .join('\n')}
                    |  esac
                    |}
                    |""".stripMargin() + command.subcommands.collect { render(it) }

        }

        s
    }

    Sting renderCaseCommand(Command subcommand) {
        """|  $subcommand.name)
           |    _${subcommand.parentName}_$subcommand.name
           |    ;;""".stripMargin()
    }

    String renderFlag(Flag flag) {
        def s = ''
        if (flag.abbreviation) {
            s += "'(-$flag.abbreviation --$flag.name)'{-$flag.abbreviation,--$flag.name}"
        } else {
            s += "'--$flag.name"
        }
        if (flag.description) {
            s += "[$flag.description]"
        }
        s += "'"
        s
    }
}

if (args.size() != 1) {
    println 'Error! Usage: complete.groovy [file]'
    System.exit(1)
}

text = new File(args[0]).text
dsl = new Dsl(text)

command = dsl.evaluate()
println command

println new CompletionRenderer(command).render()
