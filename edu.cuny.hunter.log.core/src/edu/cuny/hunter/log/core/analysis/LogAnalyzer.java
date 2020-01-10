package edu.cuny.hunter.log.core.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import edu.cuny.hunter.log.core.messages.Messages;
import edu.cuny.hunter.log.core.utils.LoggerNames;
import edu.cuny.hunter.log.core.utils.Util;

public class LogAnalyzer extends ASTVisitor {

	private static final Logger LOGGER = Logger.getLogger(LoggerNames.LOGGER_NAME);

	// Sets of log invocations for java.util.logging.
	private HashSet<LogInvocation> logInvsNotTransformedInIf = new HashSet<LogInvocation>();
	private HashSet<LogInvocation> logInvsNotLoweredInCatch = new HashSet<LogInvocation>();
	private HashSet<LogInvocation> logInvsNotLoweredInIfStatement = new HashSet<LogInvocation>();
	private HashSet<LogInvocation> logInvsNotLoweredByKeywords = new HashSet<LogInvocation>();
	private HashSet<LogInvocation> logInvsNotRaisedByKeywords = new HashSet<LogInvocation>();
	private Set<LogInvocation> logInvocationSet = new HashSet<>();

	// Sets of log invocations for slf4j.
	private HashSet<LogInvocationSlf4j> logInvsNotTransformedInIfSlf4j = new HashSet<LogInvocationSlf4j>();
	private HashSet<LogInvocationSlf4j> logInvsNotLoweredInCatchSlf4j = new HashSet<LogInvocationSlf4j>();
	private HashSet<LogInvocationSlf4j> logInvsNotLoweredInIfStatementSlf4j = new HashSet<LogInvocationSlf4j>();
	private HashSet<LogInvocationSlf4j> logInvsNotLoweredByKeywordsSlf4j = new HashSet<LogInvocationSlf4j>();
	private HashSet<LogInvocationSlf4j> logInvsNotRaisedByKeywordsSlf4j = new HashSet<LogInvocationSlf4j>();
	private Set<LogInvocationSlf4j> logInvocationSlf4j = new HashSet<LogInvocationSlf4j>();

	private HashMap<org.slf4j.event.Level, Integer> levelToIntSlf4j = new HashMap<org.slf4j.event.Level, Integer>();

	private HashMap<Level, Integer> levelToInt = new HashMap<Level, Integer>();

	private boolean notLowerLogLevelInIfStatement;

	private boolean notLowerLogLevelInCatchBlock;

	private boolean notLowerLogLevelWithKeyWords;

	private boolean notRaiseLogLevelWithoutKeyWords;

	private boolean useLogCategoryWithConfig;

	private boolean checkIfCondition;

	private boolean useLogCategory;

	private int maxTransDistance;

	private Map<IMethod, Float> methodToDOI = new HashMap<>();

	// A list of boundary for J.U.L
	private ArrayList<Float> boundary;
	// A list of boundary for slf4j.
	private ArrayList<Float> boundarySlf4j;

	/**
	 * A set of keywords in log messages for lowering log levels.
	 */
	private static final Set<String> KEYWORDS_IN_LOG_MESSAGES_FOR_LOWERING = Stream.of("fail", "disabl", "error",
			"exception", "collision", "reboot", "terminat", "throw", "should", "start", "tried", "try", "empty",
			"launch", "init", "does not", "doesn't", "did not", "didn't", "stop", "shut", "run", "deprecat", "kill",
			"finish", "ready", "wait", "dead", "alive", "creat", "debug", "info", "warn", "fatal", "severe", "config ",
			"fine", "trace", "FYI", "unknown", "could not", "cannot", "couldn't", "can't", "can not", "interrupt",
			"have no", "has no", "had no", "unsupport", "not support", "wrong", "reject", "cancel", "not recognize",
			"invalid", "timed out", "unable", "trigger", "expected", "unavailable", "not available", "reset",
			"too many", "expired", "not allowed").collect(Collectors.toSet());
	/**
	 * A set of keywords in log messages for raising log levels.
	 */
	private static final Set<String> KEYWORDS_IN_LOG_MESSAGES_FOR_RAISING = Stream
			.of("fail", "disabl", "error", "exception", "collision", "reboot", "terminat", "throw", "should have",
					"should've", "tried", "empty", "does not", "doesn't", "stop", "shut", "kill", "dead", "not alive")
			.collect(Collectors.toSet());

	private boolean test;

	public LogAnalyzer(boolean isTest) {
		this.test = isTest;
	}

	public ArrayList<Float> getBoundary() {
		return this.boundary;
	}

	public LogAnalyzer(boolean useConfigLogLevelCategory, boolean useLogLevelCategory,
			boolean notLowerLogLevelInCatchBlock, boolean checkIfCondition, boolean notLowerLogLevelInIfStatement,
			boolean notLowerLogLevelWithKeyWords, boolean notRaiseLogLevelWithoutKeywords, int maxTransDistance) {
		this.useLogCategoryWithConfig = useConfigLogLevelCategory;
		this.useLogCategory = useLogLevelCategory;
		this.notLowerLogLevelInCatchBlock = notLowerLogLevelInCatchBlock;
		this.notLowerLogLevelInIfStatement = notLowerLogLevelInIfStatement;
		this.notLowerLogLevelWithKeyWords = notLowerLogLevelWithKeyWords;
		this.notRaiseLogLevelWithoutKeyWords = notRaiseLogLevelWithoutKeywords;
		this.checkIfCondition = checkIfCondition;
		this.maxTransDistance = maxTransDistance;
	}

	public LogAnalyzer() {
	}

	public void analyze(HashSet<MethodDeclaration> methodDecsForAnalyzedMethod) {
		this.collectDOIValues(methodDecsForAnalyzedMethod);
		this.analyzeLogInvs(methodDecsForAnalyzedMethod);
	}

	/**
	 * Analyze project without git history.
	 */
	public void analyze() {
		this.collectDOIValues(Collections.emptySet());
		this.analyzeLogInvs(Collections.emptySet());
	}

	/**
	 * Build boundary and analyze log invocations.
	 */
	private void analyzeLogInvs(Set<MethodDeclaration> methodDecsForAnalyzedMethod) {
		// build boundary
		this.boundary = this.buildBoundary(this.methodToDOI.values());
		this.boundarySlf4j = this.buildBoundarySlf4j(this.methodToDOI.values());

		Set<IMethod> validMethods = this.methodToDOI.keySet();

		// It's used by the limitation of max transformation distance.
		this.createLevelToInt();
		this.createLevelToIntSlf4j();

		// check whether action is needed
		for (LogInvocation logInvocation : this.logInvocationSet) {
			// Methods not analyzed will not be considered for transformation.
			if (!validMethods.contains(logInvocation.getEnclosingEclipseMethod()))
				logInvocation.setAction(Action.NONE, null);
			else {
				if (this.checkCodeModification(logInvocation) && this.checkEnoughData(logInvocation))
					if (this.doAction(logInvocation))
						LOGGER.info("Do action: " + logInvocation.getAction() + "! The changed log expression is "
								+ logInvocation.getExpression());
			}
		}

		for (LogInvocationSlf4j logInvocationSlf4j : this.logInvocationSlf4j) {
			// Methods not analyzed will not be considered for transformation.
			if (!validMethods.contains(logInvocationSlf4j.getEnclosingEclipseMethod()))
				logInvocationSlf4j.setAction(ActionSlf4j.NONE, null);
			else {
				if (this.checkCodeModification(logInvocationSlf4j) && this.checkEnoughData(logInvocationSlf4j))
					if (this.doAction(logInvocationSlf4j))
						LOGGER.info("Do action: " + logInvocationSlf4j.getAction() + "! The changed log expression is "
								+ logInvocationSlf4j.getExpression());
			}
		}
	}

	private boolean doAction(LogInvocationSlf4j logInvocationSlf4j) {
		if (this.checkEnclosingMethod(logInvocationSlf4j))
			return false;
		if (this.checkBoundarySize(logInvocationSlf4j))
			return false;

		if (this.checkIfConditionForLogging(logInvocationSlf4j))
			return false;

		org.slf4j.event.Level currentLogLevel = logInvocationSlf4j.getLogLevel();

		// Cannot get valid log level from log invocations.
		if (currentLogLevel == null)
			return false;

		org.slf4j.event.Level rejuvenatedLogLevel = this.getRejuvenatedLogLevel(this.boundarySlf4j, logInvocationSlf4j);

		if (rejuvenatedLogLevel == null)
			return false;

		if ((currentLogLevel.equals(rejuvenatedLogLevel)) // current log level is
															// same to transformed
															// log level{
				|| (this.useLogCategory && (currentLogLevel.equals(org.slf4j.event.Level.ERROR)
						|| currentLogLevel.equals(org.slf4j.event.Level.WARN)))) // process log
																					// category
																					// ERROR/WARN
		{
			logInvocationSlf4j.setAction(ActionSlf4j.NONE, null);
			return false;
		}

		if (this.processNotLowerLogLevelWithKeyWords(logInvocationSlf4j,
				currentLogLevel.compareTo(rejuvenatedLogLevel) < 0))
			return false;

		if (this.processNotRaiseLogLevelWithKeywords(logInvocationSlf4j,
				currentLogLevel.compareTo(rejuvenatedLogLevel) > 0))
			return false;

		if (this.processNotLowerLevelInCatchBlocks(logInvocationSlf4j,
				currentLogLevel.compareTo(rejuvenatedLogLevel) < 0))
			return false;

		if (this.proceeNotLowerLogLevelInIfStatement(logInvocationSlf4j,
				currentLogLevel.compareTo(rejuvenatedLogLevel) < 0))
			return false;

		// Adjust transformations by the max transformation distance.
		rejuvenatedLogLevel = this.adjustTransformationByDistanceSlf4j(currentLogLevel, rejuvenatedLogLevel);

		if (rejuvenatedLogLevel == null) {
			logInvocationSlf4j.setAction(ActionSlf4j.NONE, null);
			return false;
		}

		if (rejuvenatedLogLevel.equals(org.slf4j.event.Level.TRACE))
			logInvocationSlf4j.setAction(ActionSlf4j.CONVERT_TO_TRACE, org.slf4j.event.Level.TRACE);
		if (rejuvenatedLogLevel.equals(org.slf4j.event.Level.DEBUG))
			logInvocationSlf4j.setAction(ActionSlf4j.CONVERT_TO_DEBUG, org.slf4j.event.Level.DEBUG);
		if (rejuvenatedLogLevel.equals(org.slf4j.event.Level.INFO))
			logInvocationSlf4j.setAction(ActionSlf4j.CONVERT_TO_INFO, org.slf4j.event.Level.INFO);
		if (rejuvenatedLogLevel.equals(org.slf4j.event.Level.WARN))
			logInvocationSlf4j.setAction(ActionSlf4j.CONVERT_TO_WARN, org.slf4j.event.Level.WARN);
		if (rejuvenatedLogLevel.equals(org.slf4j.event.Level.ERROR))
			logInvocationSlf4j.setAction(ActionSlf4j.CONVERT_TO_ERROR, org.slf4j.event.Level.ERROR);

		return true;
	}

	/**
	 * Do not change a log level in a logging statement if there exists an immediate
	 * if statement whose condition contains a log level or a case label mentioning
	 * a log level.
	 * 
	 * For log invocations in slf4j.
	 */
	private boolean checkIfConditionForLogging(LogInvocationSlf4j logInvocation) {
		if (this.checkIfCondition) {
			if (checkIfConditionHavingLevel(logInvocation.getExpression())
					|| checkCaseHavingLevel(logInvocation.getExpression())) {
				logInvocation.setAction(ActionSlf4j.NONE, null);
				this.logInvsNotTransformedInIfSlf4j.add(logInvocation);
				return true;
			}
		}
		return false;
	}

	/**
	 * Do not change a log level in a logging statement if there exists an immediate
	 * if statement whose condition contains a log level or a case label mentioning
	 * a log level.
	 * 
	 * For log invocations in J.U.L.
	 */
	private boolean checkIfConditionForLogging(LogInvocation logInvocation) {
		if (this.checkIfCondition) {
			if (checkIfConditionHavingLevel(logInvocation.getExpression())
					|| checkCaseHavingLevel(logInvocation.getExpression())) {
				logInvocation.setAction(Action.NONE, null);
				this.logInvsNotTransformedInIf.add(logInvocation);
				return true;
			}
		}
		return false;
	}

	private boolean checkBoundarySize(LogInvocation logInvocation) {
		// DOI not in intervals
		if (logInvocation.getDegreeOfInterestValue() < this.boundary.get(0)
				|| logInvocation.getDegreeOfInterestValue() > this.boundary.get(this.boundary.size() - 1)) {
			logInvocation.setAction(Action.NONE, null);
			return true;
		}
		return false;
	}

	/**
	 * For slf4j.
	 */
	private boolean checkBoundarySize(LogInvocationSlf4j logInvocation) {
		// DOI not in intervals
		if (logInvocation.getDegreeOfInterestValue() < this.boundary.get(0)
				|| logInvocation.getDegreeOfInterestValue() > this.boundary.get(this.boundary.size() - 1)) {
			logInvocation.setAction(ActionSlf4j.NONE, null);
			return true;
		}
		return false;
	}

	/**
	 * If log invocation has no enclosing method, the tool cannot currently handle
	 * it.
	 */
	private boolean checkEnclosingMethod(AbstractLogInvocation logInvocation) {
		// No enclosing method.
		if (logInvocation.getEnclosingEclipseMethod() == null) {
			logInvocation.addStatusEntry(Failure.CURRENTLY_NOT_HANDLED,
					logInvocation.getExpression() + " has no enclosing method.");
			return true;
		}
		return false;
	}

	/**
	 * Check whether all DOI values are the same.
	 */
	private boolean checkEnoughData(AbstractLogInvocation logInvocation) {
		if (this.boundary == null) {
			logInvocation.addStatusEntry(Failure.NO_ENOUGH_DATA,
					"The DOI values are all same or no DOI values. Cannot get valid results.");
			LOGGER.info("The DOI values are all same or no DOI values. Cannot get valid results.");
			return false;
		}
		return true;
	}

	/**
	 * Do transformation action for J.U.L framework.
	 */
	private boolean doAction(LogInvocation logInvocation) {

		if (this.checkEnclosingMethod(logInvocation))
			return false;
		if (this.checkBoundarySize(logInvocation))
			return false;

		if (this.checkIfConditionForLogging(logInvocation))
			return false;

		Level currentLogLevel = logInvocation.getLogLevel();

		// Cannot get valid log level from log invocations.
		if (currentLogLevel == null)
			return false;

		Level rejuvenatedLogLevel = this.getRejuvenatedLogLevel(this.boundary, logInvocation);

		if (rejuvenatedLogLevel == null)
			return false;

		if (this.processNotLowerLogLevelWithKeyWords(logInvocation,
				currentLogLevel.intValue() > rejuvenatedLogLevel.intValue()))
			return false;

		if (this.processNotRaiseLogLevelWithKeywords(logInvocation,
				currentLogLevel.intValue() < rejuvenatedLogLevel.intValue()))
			return false;

		if (this.processNotLowerLevelInCatchBlocks(logInvocation,
				currentLogLevel.intValue() > rejuvenatedLogLevel.intValue()))
			return false;

		if (this.proceeNotLowerLogLevelInIfStatement(logInvocation,
				currentLogLevel.intValue() > rejuvenatedLogLevel.intValue()))
			return false;

		if ((currentLogLevel.equals(rejuvenatedLogLevel)) // current log level is
															// same to transformed
															// log level

				|| (currentLogLevel.equals(Level.ALL) || currentLogLevel.equals(Level.OFF)) // not
																							// consider
																							// all
																							// and
																							// off

				|| (this.useLogCategory && (currentLogLevel.equals(Level.CONFIG)
						|| currentLogLevel.equals(Level.WARNING) || currentLogLevel.equals(Level.SEVERE))) // process
																											// log
																											// category
																											// (CONFIG/WARNING/SERVRE)

				|| (this.useLogCategoryWithConfig && (currentLogLevel.equals(Level.CONFIG))) // process
																								// log
																								// category
																								// (CONFIG)
		) {
			logInvocation.setAction(Action.NONE, null);
			return false;
		}

		// Adjust transformations by the max transformation distance.
		rejuvenatedLogLevel = this.adjustTransformationByDistance(currentLogLevel, rejuvenatedLogLevel);

		if (rejuvenatedLogLevel == null) {
			logInvocation.setAction(Action.NONE, null);
			return false;
		}

		if (rejuvenatedLogLevel.equals(Level.FINEST))
			logInvocation.setAction(Action.CONVERT_TO_FINEST, Level.FINEST);
		if (rejuvenatedLogLevel.equals(Level.FINER))
			logInvocation.setAction(Action.CONVERT_TO_FINER, Level.FINER);
		if (rejuvenatedLogLevel.equals(Level.FINE))
			logInvocation.setAction(Action.CONVERT_TO_FINE, Level.FINE);
		if (rejuvenatedLogLevel.equals(Level.CONFIG))
			logInvocation.setAction(Action.CONVERT_TO_CONFIG, Level.CONFIG);
		if (rejuvenatedLogLevel.equals(Level.INFO))
			logInvocation.setAction(Action.CONVERT_TO_INFO, Level.INFO);
		if (rejuvenatedLogLevel.equals(Level.WARNING))
			logInvocation.setAction(Action.CONVERT_TO_WARNING, Level.WARNING);
		if (rejuvenatedLogLevel.equals(Level.SEVERE))
			logInvocation.setAction(Action.CONVERT_TO_SEVERE, Level.SEVERE);

		return true;

	}

	/**
	 * Should not lower log level if it's logging statement in a if statement.
	 */
	private boolean proceeNotLowerLogLevelInIfStatement(LogInvocation logInvocation, boolean isLowered) {
		if (this.notLowerLogLevelInIfStatement)
			// process not lower log levels in if statements.
			if (checkIfBlock(logInvocation.getExpression()) && isLowered) {
				this.logInvsNotLoweredInIfStatement.add(logInvocation);
				logInvocation.setAction(Action.NONE, null);
				return true;
			}
		return false;
	}

	/**
	 * For slf4j.
	 * 
	 * Should not lower log level if it's logging statement in a if statement.
	 */
	private boolean proceeNotLowerLogLevelInIfStatement(LogInvocationSlf4j logInvocation, boolean isLowered) {
		if (this.notLowerLogLevelInIfStatement)
			// process not lower log levels in if statements.
			if (checkIfBlock(logInvocation.getExpression()) && isLowered) {
				this.logInvsNotLoweredInIfStatementSlf4j.add(logInvocation);
				logInvocation.setAction(ActionSlf4j.NONE, null);
				return true;
			}
		return false;
	}

	/**
	 * Should not raise log level if its corresponding log message contains
	 * keywords.
	 */
	private boolean processNotRaiseLogLevelWithKeywords(LogInvocation logInvocation, boolean isRaised) {
		if (this.notRaiseLogLevelWithoutKeyWords) {
			if (!Util.isLogMessageWithKeywords(logInvocation.getExpression(), KEYWORDS_IN_LOG_MESSAGES_FOR_RAISING)
					&& isRaised) {
				logInvocation.setAction(Action.NONE, null);
				this.logInvsNotRaisedByKeywords.add(logInvocation);
				return true;
			}
		}
		return false;
	}

	/**
	 * For slf4j.
	 * 
	 * Should not raise log level if its corresponding log message contains
	 * keywords.
	 */
	private boolean processNotRaiseLogLevelWithKeywords(LogInvocationSlf4j logInvocation, boolean isRaised) {
		if (this.notRaiseLogLevelWithoutKeyWords) {
			if (!Util.isLogMessageWithKeywordsSlf4j(logInvocation.getExpression(), KEYWORDS_IN_LOG_MESSAGES_FOR_RAISING)
					&& isRaised) {
				logInvocation.setAction(ActionSlf4j.NONE, null);
				this.logInvsNotRaisedByKeywordsSlf4j.add(logInvocation);
				return true;
			}
		}
		return false;
	}

	/**
	 * Should not lower log level if the logging statement in catch block.
	 */
	private boolean processNotLowerLevelInCatchBlocks(LogInvocation logInvocation, boolean isLowered) {
		// process not lower log levels in catch blocks
		if (logInvocation.getInCatchBlock() && isLowered) {
			this.logInvsNotLoweredInCatch.add(logInvocation);
			logInvocation.setAction(Action.NONE, null);
			return true;
		}
		return false;
	}

	/**
	 * For slf4j.
	 * 
	 * Should not lower log level if the logging statement in catch block.
	 */
	private boolean processNotLowerLevelInCatchBlocks(LogInvocationSlf4j logInvocation, boolean isLowered) {
		// process not lower log levels in catch blocks
		if (logInvocation.getInCatchBlock() && isLowered) {
			this.logInvsNotLoweredInCatchSlf4j.add(logInvocation);
			logInvocation.setAction(ActionSlf4j.NONE, null);
			return true;
		}
		return false;
	}

	/**
	 * Should not lower log level if the log message includes a keyword.
	 * 
	 * @return boolean: true means the log level is not transformed due to the
	 *         keywords
	 */
	private boolean processNotLowerLogLevelWithKeyWords(LogInvocation logInvocation, boolean isLowered) {
		if (this.notLowerLogLevelWithKeyWords) {
			if (Util.isLogMessageWithKeywords(logInvocation.getExpression(), KEYWORDS_IN_LOG_MESSAGES_FOR_LOWERING)
					&& isLowered) {
				logInvocation.setAction(Action.NONE, null);
				this.logInvsNotLoweredByKeywords.add(logInvocation);
				return true;
			}
		}
		return false;
	}

	/**
	 * For slf4j.
	 * 
	 * Should not lower log level if the log message includes a keyword.
	 * 
	 * @return boolean: true means the log level is not transformed due to the
	 *         keywords
	 */
	private boolean processNotLowerLogLevelWithKeyWords(LogInvocationSlf4j logInvocation, boolean isLowered) {
		if (this.notLowerLogLevelWithKeyWords) {
			if (Util.isLogMessageWithKeywordsSlf4j(logInvocation.getExpression(), KEYWORDS_IN_LOG_MESSAGES_FOR_LOWERING)
					&& isLowered) {
				logInvocation.setAction(ActionSlf4j.NONE, null);
				this.logInvsNotLoweredByKeywordsSlf4j.add(logInvocation);
				return true;
			}
		}
		return false;
	}

	/**
	 * Get the target log level based on boundary.
	 * 
	 * We don't need log category for slf4j now.
	 * 
	 * @param boundary
	 * @param DOI
	 * @return the target log level
	 */
	private org.slf4j.event.Level getRejuvenatedLogLevel(ArrayList<Float> boundary, LogInvocationSlf4j logInvocation) {
		float DOI = logInvocation.getDegreeOfInterestValue();
		if (boundary == null || boundary.isEmpty())
			return null;

		// If we don't consider ERROR and WARN.
		if (this.useLogCategory) {
			if (DOI >= boundary.get(0) && DOI < boundary.get(1))
				return org.slf4j.event.Level.TRACE;
			if (DOI < boundary.get(2))
				return org.slf4j.event.Level.DEBUG;
			if (DOI < boundary.get(3))
				return org.slf4j.event.Level.INFO;
		} else {
			if (DOI >= boundary.get(0) && DOI < boundary.get(1))
				return org.slf4j.event.Level.TRACE;
			if (DOI < boundary.get(2))
				return org.slf4j.event.Level.DEBUG;
			if (DOI < boundary.get(3))
				return org.slf4j.event.Level.INFO;
			if (DOI < boundary.get(4))
				return org.slf4j.event.Level.WARN;
			if (DOI < boundary.get(5))
				return org.slf4j.event.Level.ERROR;
		}
		return null;
	}

	/**
	 * Adjust transformations if their transformation distance is over the distance
	 * limitation.
	 * 
	 * @return Log level: after adjusting.
	 */
	private Level adjustTransformationByDistance(Level currentLevel, Level rejuvenatedLogLevel) {

		// Discard all transformations if allowed max transformation distance is
		// negative or equal to 0.
		if (maxTransDistance <= 0)
			return null;

		int transformationDistance = this.levelToInt.get(rejuvenatedLogLevel) - this.levelToInt.get(currentLevel);

		// if it's over the max transformation distance which are allowed by users
		if (Math.abs(transformationDistance) > this.maxTransDistance) {
			if (transformationDistance > 0)
				transformationDistance = this.maxTransDistance;
			else
				transformationDistance = -this.maxTransDistance;
		}

		int rejuveLogLevelValue = this.levelToInt.get(currentLevel) + transformationDistance;
		for (Map.Entry<Level, Integer> entry : this.levelToInt.entrySet()) {
			if (entry.getValue().equals(rejuveLogLevelValue))
				return entry.getKey();
		}

		return null;
	}

	/**
	 * For slf4j.
	 * 
	 * Adjust transformations if their transformation distance is over the distance
	 * limitation.
	 * 
	 * @return Log level: after adjusting.
	 */
	private org.slf4j.event.Level adjustTransformationByDistanceSlf4j(org.slf4j.event.Level currentLevel,
			org.slf4j.event.Level rejuvenatedLogLevel) {

		// Discard all transformations if allowed max transformation distance is
		// negative or equal to 0.
		if (maxTransDistance <= 0)
			return null;

		int transformationDistance = this.levelToIntSlf4j.get(rejuvenatedLogLevel)
				- this.levelToIntSlf4j.get(currentLevel);

		// if it's over the max transformation distance which are allowed by users
		if (Math.abs(transformationDistance) > this.maxTransDistance) {
			if (transformationDistance > 0)
				transformationDistance = this.maxTransDistance;
			else
				transformationDistance = -this.maxTransDistance;
		}

		int rejuveLogLevelValue = this.levelToIntSlf4j.get(currentLevel) + transformationDistance;
		for (Map.Entry<org.slf4j.event.Level, Integer> entry : this.levelToIntSlf4j.entrySet()) {
			if (entry.getValue().equals(rejuveLogLevelValue))
				return entry.getKey();
		}

		return null;
	}

	/**
	 * Maintain a map from log level to an integer value. We are using this map to
	 * calculate transformation distance.
	 */
	private void createLevelToInt() {
		if (this.useLogCategory) {
			this.levelToInt.put(Level.FINEST, 0);
			this.levelToInt.put(Level.FINER, 1);
			this.levelToInt.put(Level.FINE, 2);
			this.levelToInt.put(Level.INFO, 3);
		} else if (this.useLogCategoryWithConfig) {
			this.levelToInt.put(Level.FINEST, 0);
			this.levelToInt.put(Level.FINER, 1);
			this.levelToInt.put(Level.FINE, 2);
			this.levelToInt.put(Level.INFO, 3);
			this.levelToInt.put(Level.WARNING, 4);
			this.levelToInt.put(Level.SEVERE, 5);
		} else {
			this.levelToInt.put(Level.FINEST, 0);
			this.levelToInt.put(Level.FINER, 1);
			this.levelToInt.put(Level.FINE, 2);
			this.levelToInt.put(Level.CONFIG, 3);
			this.levelToInt.put(Level.INFO, 4);
			this.levelToInt.put(Level.WARNING, 5);
			this.levelToInt.put(Level.SEVERE, 6);
		}
	}

	/**
	 * Maintain a map from log level to an integer value. We are using this map to
	 * calculate transformation distance.
	 * 
	 * For slf4j.
	 */
	private void createLevelToIntSlf4j() {
		if (this.useLogCategory) {
			this.levelToIntSlf4j.put(org.slf4j.event.Level.TRACE, 0);
			this.levelToIntSlf4j.put(org.slf4j.event.Level.DEBUG, 1);
			this.levelToIntSlf4j.put(org.slf4j.event.Level.INFO, 2);
		} else {
			this.levelToIntSlf4j.put(org.slf4j.event.Level.TRACE, 0);
			this.levelToIntSlf4j.put(org.slf4j.event.Level.DEBUG, 1);
			this.levelToIntSlf4j.put(org.slf4j.event.Level.INFO, 2);
			this.levelToIntSlf4j.put(org.slf4j.event.Level.WARN, 3);
			this.levelToIntSlf4j.put(org.slf4j.event.Level.ERROR, 2);
		}
	}

	/**
	 * Get the rejuvenated log level based on boundary.
	 * 
	 * @param boundary
	 * @param DOI
	 * @return the rejuvenated log level
	 */
	private Level getRejuvenatedLogLevel(ArrayList<Float> boundary, LogInvocation logInvocation) {
		float DOI = logInvocation.getDegreeOfInterestValue();
		if (boundary == null || boundary.isEmpty())
			return null;

		if (this.useLogCategory) {
			LOGGER.info("Use log category: do not consider config/warning/severe.");
			if (DOI >= boundary.get(0) && DOI < boundary.get(1))
				return Level.FINEST;
			if (DOI < boundary.get(2))
				return Level.FINER;
			if (DOI < boundary.get(3))
				return Level.FINE;
			if (DOI <= boundary.get(4))
				return Level.INFO;
		} else if (this.useLogCategoryWithConfig) {
			LOGGER.info("Use log category: do not consider config.");
			if (DOI >= boundary.get(0) && DOI < boundary.get(1))
				return Level.FINEST;
			if (DOI < boundary.get(2))
				return Level.FINER;
			if (DOI < boundary.get(3))
				return Level.FINE;
			if (DOI < boundary.get(4))
				return Level.INFO;
			if (DOI < boundary.get(5))
				return Level.WARNING;
			if (DOI <= boundary.get(6))
				return Level.SEVERE;
		} else {
			LOGGER.info("Default log category.");
			if (DOI >= boundary.get(0) && DOI < boundary.get(1))
				return Level.FINEST;
			if (DOI < boundary.get(2))
				return Level.FINER;
			if (DOI < boundary.get(3))
				return Level.FINE;
			if (DOI < boundary.get(4))
				return Level.CONFIG;
			if (DOI < boundary.get(5))
				return Level.INFO;
			if (DOI < boundary.get(6))
				return Level.WARNING;
			if (DOI <= boundary.get(7))
				return Level.SEVERE;
		}
		return null;
	}

	/**
	 * Build a list of boundary. The DOI values could be divided into 7 groups by
	 * this boundary. 7 groups are corresponding to 7 logging levels
	 * 
	 * @param degreeOfInterests
	 * @return a list of boundary
	 */
	private ArrayList<Float> buildBoundary(Collection<Float> degreeOfInterests) {
		float min = getMinDOI(degreeOfInterests);
		float max = getMaxDOI(degreeOfInterests);
		ArrayList<Float> boundary = new ArrayList<>();
		if (min < max) {
			if (this.useLogCategory) {
				float interval = (max - min) / 4;
				IntStream.range(0, 4).forEach(i -> boundary.add(min + i * interval));
				boundary.add(max);
			} else if (this.useLogCategoryWithConfig) {
				float interval = (max - min) / 6;
				IntStream.range(0, 6).forEach(i -> boundary.add(min + i * interval));
				boundary.add(max);
			} else {
				float interval = (max - min) / 7;
				IntStream.range(0, 7).forEach(i -> boundary.add(min + i * interval));
				boundary.add(max);
			}
			return boundary;
		} else
			return null;
	}

	/**
	 * Build a list of boundary for the log transformations in Slf4j. The DOI values
	 * could be divided into 5 groups. Each group is corresponding to log level
	 * ERROR, WARN, INFO, DEBUG and TRACE respectively.
	 * 
	 * @param degreeOfInterests: a list of DOI values for all methods.
	 * @return a list of boundary for slf4j.
	 */
	private ArrayList<Float> buildBoundarySlf4j(Collection<Float> degreeOfInterests) {
		float min = getMinDOI(degreeOfInterests);
		float max = getMaxDOI(degreeOfInterests);
		ArrayList<Float> boundarySlf4j = new ArrayList<>();
		if (min < max) {
			// Don't consider ERROR and WARN, i.e., we only consider INFO, DEBUG and TRACE
			if (this.useLogCategory) {
				float interval = (max - min) / 3;
				IntStream.range(0, 3).forEach(i -> boundarySlf4j.add(min + i * interval));
				boundarySlf4j.add(max);
			} else {
				float interval = (max - min) / 5;
				IntStream.range(0, 5).forEach(i -> boundarySlf4j.add(min + i * interval));
				boundarySlf4j.add(max);
			}
			return boundarySlf4j;
		} else
			return null;
	}

	/**
	 * Get the minimum of DOIs
	 * 
	 * @param degreeOfInterests
	 */
	private float getMinDOI(Collection<Float> degreeOfInterests) {
		float min = 0;
		for (float d : degreeOfInterests)
			if (d < min)
				min = d;
		return min;
	}

	private float getMaxDOI(Collection<Float> degreeOfInterests) {
		float max = 0;
		for (float d : degreeOfInterests)
			if (d > max)
				max = d;
		return max;
	}

	public Set<LogInvocation> getLogInvocationSet() {
		return this.logInvocationSet;
	}

	public void setTest(boolean test) {
		this.test = test;
	}

	/**
	 * This method is used to find a set of logging objects
	 */
	@Override
	public boolean visit(MethodInvocation node) {

		LogLevel logLevel = null;

		try {
			logLevel = Util.isLogExpression(node, test);
		} catch (IllegalStateException e) {
			LOGGER.warning("Need to process the variable of logging level or LogRecord!");
			this.createLogInvocation(node, null, false);
			return super.visit(node);
		}

		if (logLevel != null)
			this.createLogInvocation(node, logLevel,
					this.notLowerLogLevelInCatchBlock && this.checkLogInCatchBlock(node));

		return super.visit(node);
	}

	/**
	 * Check whether logging statements are in catch blocks.
	 */
	private boolean checkLogInCatchBlock(ASTNode node) {
		while (node != null) {
			if (node instanceof CatchClause)
				return true;
			node = node.getParent();
		}
		return false;
	}

	/**
	 * Check if condition mentions log levels.
	 */
	private static boolean checkIfConditionHavingLevel(ASTNode node) {
		while (node != null) {
			if (node instanceof IfStatement) {
				Expression condition = ((IfStatement) node).getExpression();
				if (containingKeywords(condition)) {
					LOGGER.info("We meet a logging wrapping: \n" + node);
					return true;
				}
			}
			node = node.getParent();
		}
		return false;
	}

	/**
	 * Check case label mentions log levels.
	 */
	private static boolean checkCaseHavingLevel(ASTNode node) {
		int nodePosition = node.getStartPosition();
		while (node != null) {
			if (node instanceof SwitchStatement) {
				SwitchStatement switchStatement = (SwitchStatement) node;

				Map<Integer, SwitchCase> postionToSwitchCase = new HashMap<Integer, SwitchCase>();
				// get a list of positions for switch cases
				switchStatement.accept(new ASTVisitor() {
					@Override
					public boolean visit(SwitchCase switchCase) {
						postionToSwitchCase.put(switchCase.getStartPosition(), switchCase);
						return true;
					}
				});

				// get the corresponding case label for the log invocation.
				Set<Integer> positions = postionToSwitchCase.keySet();
				// tmp is the max poisition before nodePosition
				int tmp = 0;
				for (int p : positions)
					if (p < nodePosition && p >= tmp) {
						tmp = p;
					}
				if (tmp == 0)
					return false;
				SwitchCase switchCase = postionToSwitchCase.get(tmp);

				// check whether the case label contains the keywords
				if (containingKeywords(switchCase.toString())) {
					LOGGER.info("We meet a logging wrapping: \n" + node);
					return true;
				}
				return false;
			}
			node = node.getParent();
		}
		return false;
	}

	/**
	 * Check whether if condition or case label contains a particular log level.
	 */
	private static boolean containingKeywords(Object expression) {
		String label = expression.toString().toUpperCase();
		if (label.contains("CONFIG") || label.contains("FINE") || label.contains("FINER") || label.contains("FINEST")
				|| label.contains("SEVERE") || label.contains("WARN") || label.contains("INFO")
				|| label.contains("FATAL") || label.contains("ERROR") || label.contains("DEBUG")
				|| label.contains("TRACE"))
			return true;
		else
			return false;

	}

	/**
	 * Returns true if the given logging expression is immediately contained within
	 * an if statement not having an else clause (i.e., guarded) and false
	 * otherwise.
	 */
	private static boolean checkIfBlock(MethodInvocation loggingExpression) {
		ASTNode loggingStatement = loggingExpression.getParent();
		ASTNode parent = loggingStatement.getParent();

		if (parent instanceof Block)
			parent = parent.getParent();

		if (parent instanceof IfStatement) {
			IfStatement ifStatement = (IfStatement) parent;
			Statement elseStatement = ifStatement.getElseStatement();

			// if there's no else clause
			if (elseStatement == null) {
				// is it the first statement of the then statement?
				Statement thenStatement = ifStatement.getThenStatement();

				// if it's a block.
				if (thenStatement.getNodeType() == ASTNode.BLOCK) {
					Block block = (Block) thenStatement;

					// if the block is non-empty.
					if (block.statements().size() > 0) {
						// if it's in the first statement of the block.
						Statement firstStatement = (Statement) block.statements().get(0);

						return firstStatement.getNodeType() != ASTNode.BLOCK
								&& contains(firstStatement, loggingExpression);
					} else
						// it's an empty block.
						throw new IllegalStateException("Block shouldn't be empty.");
				} else
					// it's not a block. Just check the statement.
					return contains(thenStatement, loggingExpression);
			} else
				// there's an else clause. It's not a guarded comment.
				return false;
		} else
			return false;
	}

	private static boolean contains(Statement statement, MethodInvocation loggingExpression) {
		if (statement == null || loggingExpression == null)
			return false;
		else {
			int elseStart = statement.getStartPosition();
			int elseEnd = elseStart + statement.getLength();

			int loggingExpressionStart = loggingExpression.getStartPosition();
			int loggingExpressionEnd = loggingExpressionStart + loggingExpression.getLength();

			return elseStart <= loggingExpressionStart && elseEnd >= loggingExpressionEnd;
		}
	}

	/**
	 * We only consider analyzed methods.
	 */
	private void collectDOIValues(Set<MethodDeclaration> methods) {
		Set<IMethod> enclosingMethods = getEnclosingMethods();
		methods.forEach(m -> {
			IMethodBinding methodBinding = m.resolveBinding();
			if (methodBinding != null) {
				IMethod method = (IMethod) methodBinding.getJavaElement();
				float doiValue = Util.getDOIValue(method, enclosingMethods);
				this.methodToDOI.put(method, doiValue);
			}
		});
	}

	public Set<IMethod> getEnclosingMethods() {
		return this.getLogInvocationSet().parallelStream().map(LogInvocation::getEnclosingEclipseMethod)
				.filter(Objects::nonNull).collect(Collectors.toSet());
	}

	/**
	 * Whether the code is read-only, generated code and in a .class file.
	 */
	public boolean checkCodeModification(AbstractLogInvocation logInvocation) {

		CompilationUnit cu = logInvocation.getEnclosingCompilationUnit();
		if (cu != null) {
			IJavaElement element = cu.getJavaElement();
			if (element.isReadOnly()) {
				logInvocation.addStatusEntry(Failure.READ_ONLY_ELEMENT, Messages.ReadOnlyElement);
				return false;
			}

			IMethod method = logInvocation.getEnclosingEclipseMethod();
			if (method != null && method.isBinary()) {
				logInvocation.addStatusEntry(Failure.BINARY_ELEMENT, Messages.BinaryElement);
				return false;
			}

			try {
				if (Util.isGeneratedCode(element)) {
					logInvocation.addStatusEntry(Failure.GENERATED_ELEMENT, Messages.GeneratedElement);
					return false;
				}
			} catch (JavaModelException e) {
				logInvocation.addStatusEntry(Failure.MISSING_JAVA_ELEMENT, Messages.MissingJavaElement);
				return false;
			}
		} else {
			LOGGER.warning("Can't find enclosing compilation unit for: " + logInvocation + ".");
			logInvocation.addStatusEntry(Failure.MISSING_JAVA_ELEMENT, Messages.MissingJavaElement);
			return false;
		}
		return true;

	}

	/**
	 * Create a set of input log invocations.
	 */
	private void createLogInvocation(MethodInvocation node, LogLevel logLevel, boolean inCatchBlock) {
		if (logLevel == null) {
			LogInvocation logInvocation = new LogInvocation(node, null, inCatchBlock);
			this.getLogInvocationSet().add(logInvocation);
		} else if (logLevel.framework.equals(LoggingFramework.JAVA_UTIL_LOGGING)) {
			LogInvocation logInvocation = new LogInvocation(node, logLevel.getLogLevel(), inCatchBlock);
			this.getLogInvocationSet().add(logInvocation);
		} else if (logLevel.framework.equals(LoggingFramework.SLF4J)) {
			LogInvocationSlf4j logInvocationSlf4j = new LogInvocationSlf4j(node, logLevel.getSlf4jLevel(),
					inCatchBlock);
			this.getLogInvocationSlf4js().add(logInvocationSlf4j);
		}
	}

	public void updateDOI() {
		this.getLogInvocationSet().forEach(inv -> {
			inv.updateDOI();
		});
		this.getLogInvocationSlf4js().forEach(inv -> {
			inv.updateDOI();
		});
	}

	public HashSet<LogInvocation> getLogInvsNotTransformedInIf() {
		return this.logInvsNotTransformedInIf;
	}

	public HashSet<LogInvocation> getLogInvsNotLoweredInCatch() {
		return this.logInvsNotLoweredInCatch;
	}

	public HashSet<LogInvocation> getLogInvsNotLoweredInIfStatement() {
		return this.logInvsNotLoweredInIfStatement;
	}

	public HashSet<LogInvocation> getLogInvsNotLoweredByKeywords() {
		return this.logInvsNotLoweredByKeywords;
	}

	public HashSet<LogInvocation> getLogInvsNotRaisedByKeywords() {
		return this.logInvsNotRaisedByKeywords;
	}

	public Map<IMethod, Float> getMethodToDOI() {
		return this.methodToDOI;
	}

	public Set<LogInvocationSlf4j> getLogInvocationSlf4js() {
		return this.logInvocationSlf4j;
	}

	public void setLogInvocationsSlf4js(Set<LogInvocationSlf4j> logInvocationSlf4j) {
		this.logInvocationSlf4j = logInvocationSlf4j;
	}

	public HashSet<LogInvocationSlf4j> getLogInvsNotLoweredByKeywordsSlf4j() {
		return this.logInvsNotLoweredByKeywordsSlf4j;
	}

	public HashSet<LogInvocationSlf4j> getLogInvsNotLoweredInCatchSlf4j() {
		return this.logInvsNotLoweredInCatchSlf4j;
	}

	public HashSet<LogInvocationSlf4j> getLogInvsNotLoweredInIfStatementsSlf4j() {
		return this.logInvsNotLoweredInIfStatementSlf4j;
	}

	public HashSet<LogInvocationSlf4j> getLogInvsNotRaisedByKeywordsSlf4j() {
		return this.logInvsNotRaisedByKeywordsSlf4j;
	}

	public HashSet<LogInvocationSlf4j> getLogInvsNotTransformedInIfSlf4j() {
		return this.logInvsNotTransformedInIfSlf4j;
	}

	public ArrayList<Float> getBoundarySlf4j() {
		return this.boundarySlf4j;
	}
}
