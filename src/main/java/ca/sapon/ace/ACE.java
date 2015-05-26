/*
 * This file is part of ACE, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015-2015 Aleksi Sapon <http://sapon.ca/JICI/>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package ca.sapon.ace;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.google.common.base.Optional;
import com.google.inject.Inject;

import ca.sapon.jici.SourceException;
import ca.sapon.jici.SourceMetadata;
import ca.sapon.jici.decoder.Decoder;
import ca.sapon.jici.evaluator.Environment;
import ca.sapon.jici.evaluator.Environment.Variable;
import ca.sapon.jici.evaluator.value.ObjectValue;
import ca.sapon.jici.evaluator.value.Value;
import ca.sapon.jici.evaluator.value.type.ObjectValueType;
import ca.sapon.jici.evaluator.value.type.ValueType;
import ca.sapon.jici.lexer.Identifier;
import ca.sapon.jici.lexer.Lexer;
import ca.sapon.jici.lexer.Token;
import ca.sapon.jici.lexer.TokenID;
import ca.sapon.jici.parser.Parser;
import ca.sapon.jici.parser.expression.Expression;
import ca.sapon.jici.parser.statement.Statement;
import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.event.Subscribe;
import org.spongepowered.api.event.state.ServerStartingEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.service.command.CommandService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.Texts;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.util.command.CommandCallable;
import org.spongepowered.api.util.command.CommandException;
import org.spongepowered.api.util.command.CommandResult;
import org.spongepowered.api.util.command.CommandSource;

@Plugin(id = "ace", name = "ACE", version = "0.0.1")
public class ACE {
    private static final int ENTRIES_PER_PAGE = 5;
    @Inject
    private Logger logger;
    @Inject
    private Game game;
    @Inject
    private PluginContainer instance;
    private final Map<CommandSource, Environment> environments = new WeakHashMap<CommandSource, Environment>();

    @Subscribe
    public void onServerStarting(ServerStartingEvent event) {
        final CommandService commandDispatcher = game.getCommandDispatcher();
        commandDispatcher.register(instance, new ACEEval(), "acee");
        commandDispatcher.register(instance, new ACEContext(), "acec");
    }

    private void eval(CommandSource source, String code) {
        Environment environment = environments.get(source);
        if (environment == null) {
            environment = new Environment();
            addVariable(environment, "game", game);
            addVariable(environment, "me", source);
            addVariable(environment, "printer", new Printer(source));
            environments.put(source, environment);
        }
        final SourceMetadata metadata = new SourceMetadata(code);
        try {
            code = Decoder.decode(code, metadata);
            final List<Token> tokens = Lexer.lex(code);
            if (tokens.isEmpty()) {
                source.sendMessage(Texts.of(TextColors.DARK_RED, "Nothing to evaluate"));
                return;
            }
            if (tokens.get(tokens.size() - 1).getID() == TokenID.SYMBOL_SEMICOLON) {
                final List<Statement> statements = Parser.parse(tokens);
                for (Statement statement : statements) {
                    statement.execute(environment);
                }
                source.sendMessage(Texts.of(TextColors.DARK_GREEN, "Success"));
            } else {
                final Expression expression = Parser.parseExpression(tokens);
                final ValueType type = expression.getValueType(environment);
                final Value value = expression.getValue(environment);
                source.sendMessage(Texts.of(
                        TextColors.DARK_GREEN, "Type: ", TextColors.RESET, type.toString(),
                        TextColors.DARK_GREEN, " Value: ", TextColors.RESET, value.toString()
                ));
            }
        } catch (SourceException exception) {
            printMultiLineMessage(source, metadata.generateErrorMessage(exception));
        } catch (Exception exception) {
            source.sendMessage(Texts.of(TextColors.DARK_RED, "Unknown exception, see console"));
            logger.error("Error while evaluating code", exception);
        }
    }

    private void printMultiLineMessage(CommandSource source, String message) {
        final String[] lines = message.split("\n");
        for (String line : lines) {
            source.sendMessage(Texts.of(TextColors.DARK_RED, line));
        }
    }

    private void printImports(CommandSource source, int page) {
        if (validateInfoArguments(source, page)) {
            printEntries(source, environments.get(source).getClasses(), page);
        }
    }

    private void printVariables(CommandSource source, int page) {
        if (validateInfoArguments(source, page)) {
            printEntries(source, environments.get(source).getVariables(), page);
        }
    }

    private void resetEnvironment(CommandSource source) {
        final Environment environment = environments.remove(source);
        source.sendMessage(environment == null
                        ? Texts.of(TextColors.DARK_RED, "No active context")
                        : Texts.of(TextColors.DARK_GREEN, "Context deleted")
        );
    }

    private void printEntries(CommandSource source, Collection<?> entries, int page) {
        final int start = (page - 1) * ENTRIES_PER_PAGE;
        final int end = Math.min(start + ENTRIES_PER_PAGE, entries.size());
        if (end <= start) {
            source.sendMessage(Texts.of(TextColors.DARK_RED, "No entries for page ", page));
            return;
        }
        final Iterator<?> iterator = entries.iterator();
        for (int i = 0; i < end; i++) {
            final Object entry = iterator.next();
            if (i >= start) {
                source.sendMessage(entryToText(entry));
            }
        }
    }

    private Text entryToText(Object entry) {
        if (entry instanceof Class) {
            return Texts.of(TextColors.DARK_GREEN, ((Class) entry).getCanonicalName());
        }
        if (entry instanceof Variable) {
            final Variable variable = (Variable) entry;
            final Text valueText = variable.initialized() ? Texts.of(TextColors.DARK_GREEN, " Value: ", TextColors.RESET, variable.getValue().toString()) : Texts.of();
            return Texts.of(
                    TextColors.DARK_GREEN, "Name: ", TextColors.RESET, variable.getName(),
                    TextColors.DARK_GREEN, " Type: ", TextColors.RESET, variable.getType().toString(),
                    valueText
            );
        }
        return Texts.of(entry);
    }

    private boolean validateInfoArguments(CommandSource source, int page) {
        if (!environments.containsKey(source)) {
            source.sendMessage(Texts.of(TextColors.DARK_RED, "No active context"));
            return false;
        }
        if (page < 1) {
            source.sendMessage(Texts.of(TextColors.DARK_RED, "Page must be a number greater or equal to 1"));
            return false;
        }
        return true;
    }

    private static void addVariable(Environment environment, String name, Object variable) {
        environment.declareVariable(Identifier.from(name, 0), ObjectValueType.of(variable.getClass()), ObjectValue.of(variable));
    }

    private class ACEEval implements CommandCallable {
        @Override
        public List<String> getSuggestions(CommandSource source, String arguments) throws CommandException {
            return Collections.emptyList();
        }

        @Override
        public boolean testPermission(CommandSource source) {
            return source.hasPermission("ace.eval");
        }

        @Override
        public Optional<Text> getShortDescription(CommandSource source) {
            return Optional.of((Text) Texts.of("Evaluates Java expressions and statement"));
        }

        @Override
        public Optional<Text> getHelp(CommandSource source) {
            return Optional.of(getUsage(source));
        }

        @Override
        public Text getUsage(CommandSource source) {
            return Texts.of("acee expression | statement");
        }

        @Override
        public Optional<CommandResult> process(CommandSource source, String argumentString) throws CommandException {
            eval(source, argumentString);
            return Optional.of(CommandResult.success());
        }
    }

    private class ACEContext implements CommandCallable {
        @Override
        public List<String> getSuggestions(CommandSource source, String arguments) throws CommandException {
            return Collections.emptyList();
        }

        @Override
        public boolean testPermission(CommandSource source) {
            return source.hasPermission("ace.context");
        }

        @Override
        public Optional<Text> getShortDescription(CommandSource source) {
            return Optional.of((Text) Texts.of("Manage the source's ACE context"));
        }

        @Override
        public Optional<Text> getHelp(CommandSource source) {
            return Optional.of(getUsage(source));
        }

        @Override
        public Text getUsage(CommandSource source) {
            return Texts.of("imports [page] | variables [page] | reset");
        }

        @Override
        public Optional<CommandResult> process(CommandSource source, String argumentString) throws CommandException {
            final String[] arguments = argumentString.split(" ");
            final String command = arguments[0];
            final int page = arguments.length >= 2 ? tryParseInt(arguments[1]) : 1;
            if (command.equals("imports")) {
                printImports(source, page);
                return Optional.of(CommandResult.success());
            }
            if (command.equals("variables")) {
                printVariables(source, page);
                return Optional.of(CommandResult.success());
            }
            if (command.equals("reset")) {
                resetEnvironment(source);
                return Optional.of(CommandResult.success());
            }
            throw new CommandException(Texts.of("Unknown command: ", command));
        }

        private int tryParseInt(String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException exception) {
                return -1;
            }
        }
    }

    public static class Printer {
        private final CommandSource receiver;

        private Printer(CommandSource receiver) {
            this.receiver = receiver;
        }

        public void print(boolean message) {
            print(Boolean.toString(message));
        }

        public void print(byte message) {
            print(Byte.toString(message));
        }

        public void print(short message) {
            print(Short.toString(message));
        }

        public void print(char message) {
            print(Character.toString(message));
        }

        public void print(int message) {
            print(Integer.toString(message));
        }

        public void print(long message) {
            print(Long.toString(message));
        }

        public void print(float message) {
            print(Float.toString(message));
        }

        public void print(double message) {
            print(Double.toString(message));
        }

        public void print(Object message) {
            print(message.toString());
        }

        public void print(String message) {
            receiver.sendMessage(Texts.of(message));
        }
    }
}
