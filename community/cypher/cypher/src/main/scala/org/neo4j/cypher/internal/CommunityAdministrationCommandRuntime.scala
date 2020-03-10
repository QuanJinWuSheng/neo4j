/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal

import org.neo4j.common.DependencyResolver
import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.cypher.internal.compiler.phases.LogicalPlanState
import org.neo4j.cypher.internal.logical.plans.AssertDatabaseAdmin
import org.neo4j.cypher.internal.logical.plans.AssertDbmsAdmin
import org.neo4j.cypher.internal.logical.plans.AssertDbmsAdminOrSelf
import org.neo4j.cypher.internal.logical.plans.AssertNotCurrentUser
import org.neo4j.cypher.internal.logical.plans.CreateUser
import org.neo4j.cypher.internal.logical.plans.DoNothingIfExists
import org.neo4j.cypher.internal.logical.plans.DoNothingIfNotExists
import org.neo4j.cypher.internal.logical.plans.DropUser
import org.neo4j.cypher.internal.logical.plans.EnsureNodeExists
import org.neo4j.cypher.internal.logical.plans.LogSystemCommand
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.SetOwnPassword
import org.neo4j.cypher.internal.logical.plans.ShowDatabase
import org.neo4j.cypher.internal.logical.plans.ShowDatabases
import org.neo4j.cypher.internal.logical.plans.ShowDefaultDatabase
import org.neo4j.cypher.internal.logical.plans.ShowUsers
import org.neo4j.cypher.internal.logical.plans.SystemProcedureCall
import org.neo4j.cypher.internal.procs.AdminActionMapper
import org.neo4j.cypher.internal.procs.AuthorizationPredicateExecutionPlan
import org.neo4j.cypher.internal.procs.PredicateExecutionPlan
import org.neo4j.cypher.internal.procs.QueryHandler
import org.neo4j.cypher.internal.procs.SystemCommandExecutionPlan
import org.neo4j.cypher.internal.procs.UpdatingSystemCommandExecutionPlan
import org.neo4j.cypher.internal.runtime.ParameterMapping
import org.neo4j.cypher.internal.runtime.slottedParameters
import org.neo4j.cypher.internal.security.SystemGraphCredential
import org.neo4j.exceptions.CantCompileQueryException
import org.neo4j.exceptions.DatabaseAdministrationOnFollowerException
import org.neo4j.exceptions.Neo4jException
import org.neo4j.graphdb.Direction
import org.neo4j.graphdb.Label
import org.neo4j.graphdb.Node
import org.neo4j.graphdb.RelationshipType.withName
import org.neo4j.graphdb.security.AuthorizationViolationException.PERMISSION_DENIED
import org.neo4j.internal.kernel.api.security.AdminActionOnResource
import org.neo4j.internal.kernel.api.security.AdminActionOnResource.DatabaseScope
import org.neo4j.internal.kernel.api.security.PrivilegeAction
import org.neo4j.internal.kernel.api.security.SecurityContext
import org.neo4j.internal.kernel.api.security.Segment
import org.neo4j.kernel.api.KernelTransaction
import org.neo4j.kernel.api.exceptions.InvalidArgumentsException
import org.neo4j.kernel.api.exceptions.Status
import org.neo4j.kernel.api.exceptions.Status.HasStatus
import org.neo4j.values.storable.ByteArray
import org.neo4j.values.storable.TextValue
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.MapValue
import org.neo4j.values.virtual.VirtualValues

import scala.collection.JavaConverters.asScalaIteratorConverter

/**
 * This runtime takes on queries that require no planning, such as multidatabase administration commands
 */
case class CommunityAdministrationCommandRuntime(normalExecutionEngine: ExecutionEngine, resolver: DependencyResolver,
                                                 extraLogicalToExecutable: PartialFunction[LogicalPlan, (RuntimeContext, ParameterMapping) => ExecutionPlan] = CommunityAdministrationCommandRuntime.emptyLogicalToExecutable
                                                ) extends AdministrationCommandRuntime {
  override def name: String = "community administration-commands"

  def throwCantCompile(unknownPlan: LogicalPlan): Nothing = {
    throw new CantCompileQueryException(
      s"Plan is not a recognized database administration command in community edition: ${unknownPlan.getClass.getSimpleName}")
  }

  override def compileToExecutable(state: LogicalQuery, context: RuntimeContext, securityContext: SecurityContext): ExecutionPlan = {

    val (planWithSlottedParameters, parameterMapping) = slottedParameters(state.logicalPlan)

    // Either the logical plan is a command that the partial function logicalToExecutable provides/understands OR we throw an error
    logicalToExecutable.applyOrElse(planWithSlottedParameters, throwCantCompile).apply(context, parameterMapping)
  }

  // When the community commands are run within enterprise, this allows the enterprise commands to be chained
  private def fullLogicalToExecutable = extraLogicalToExecutable orElse logicalToExecutable

  def logicalToExecutable: PartialFunction[LogicalPlan, (RuntimeContext, ParameterMapping) => ExecutionPlan] = {

    // Check Admin Rights for DBMS commands
    case AssertDbmsAdmin(actions@_*) => (_, _) =>
      AuthorizationPredicateExecutionPlan((_, securityContext) => actions.forall { action =>
        securityContext.allowsAdminAction(new AdminActionOnResource(AdminActionMapper.asKernelAction(action), DatabaseScope.ALL, Segment.ALL))
      }, violationMessage = PERMISSION_DENIED)

    // Check Admin Rights for DBMS commands or self
    case AssertDbmsAdminOrSelf(user, actions@_*) => (_, _) =>
      AuthorizationPredicateExecutionPlan((_, securityContext) => securityContext.subject().hasUsername(user) || actions.forall { action =>
        securityContext.allowsAdminAction(new AdminActionOnResource(AdminActionMapper.asKernelAction(action), DatabaseScope.ALL, Segment.ALL))
      }, violationMessage = PERMISSION_DENIED)

    // Check that the specified user is not the logged in user (eg. for some ALTER USER commands)
    case AssertNotCurrentUser(source, userName, verb, violationMessage) => (context, parameterMapping) =>
      new PredicateExecutionPlan((params, sc) => !sc.subject().hasUsername(runtimeValue(userName, params)),
        onViolation = (_, sc) => new InvalidArgumentsException(s"Failed to $verb the specified user '${sc.subject().username()}': $violationMessage."),
        source = Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context, parameterMapping))
      )

    // Check Admin Rights for some Database commands
    case AssertDatabaseAdmin(action, database) => (_, _) =>
      AuthorizationPredicateExecutionPlan((_, securityContext) =>
        securityContext.allowsAdminAction(new AdminActionOnResource(AdminActionMapper.asKernelAction(action), new DatabaseScope(database.name()), Segment.ALL)),
        violationMessage = PERMISSION_DENIED
      )

    // SHOW USERS
    case ShowUsers(source) => (context, parameterMapping) =>
      SystemCommandExecutionPlan("ShowUsers", normalExecutionEngine,
        """MATCH (u:User)
          |RETURN u.name as user, u.passwordChangeRequired AS passwordChangeRequired""".stripMargin,
        VirtualValues.EMPTY_MAP,
        source = Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context, parameterMapping))
      )

    // CREATE [OR REPLACE] USER foo [IF NOT EXISTS] SET PASSWORD 'password'
    // CREATE [OR REPLACE] USER foo [IF NOT EXISTS] SET PASSWORD $password
    case CreateUser(source, userName, password, requirePasswordChange, suspendedOptional) => (context, parameterMapping) =>
      val sourcePlan: Option[ExecutionPlan] = Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context, parameterMapping))
      if (suspendedOptional.isDefined) { // Users are always active in community
        new PredicateExecutionPlan((_, _) => false, sourcePlan, (params, _) => {
          val user = runtimeValue(userName, params)
          throw new CantCompileQueryException(s"Failed to create the specified user '$user': 'SET STATUS' is not available in community edition.")
        })
      }
      else {
        makeCreateUserExecutionPlan(userName, password, requirePasswordChange, suspended = false)(sourcePlan, normalExecutionEngine)
      }

    // DROP USER foo [IF EXISTS]
    case DropUser(source, userName) => (context, parameterMapping) =>
      val userNameValue = makeParameterValue(userName)
      UpdatingSystemCommandExecutionPlan("DropUser", normalExecutionEngine,
        """MATCH (user:User {name: $name}) DETACH DELETE user
          |RETURN 1 AS ignore""".stripMargin,
        VirtualValues.map(Array("name"), Array(userNameValue)),
        QueryHandler
          .handleError {
            case (error: HasStatus, _) if error.status() == Status.Cluster.NotALeader =>
              new DatabaseAdministrationOnFollowerException(s"Failed to delete the specified user '$userName': $followerError", error)
            case (error, _) => new IllegalStateException(s"Failed to delete the specified user '$userName'.", error)
          },
        Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context, parameterMapping))
      )

    // ALTER CURRENT USER SET PASSWORD FROM 'currentPassword' TO 'newPassword'
    // ALTER CURRENT USER SET PASSWORD FROM 'currentPassword' TO $newPassword
    // ALTER CURRENT USER SET PASSWORD FROM $currentPassword TO 'newPassword'
    // ALTER CURRENT USER SET PASSWORD FROM $currentPassword TO $newPassword
    case SetOwnPassword(newPassword, currentPassword) => (_, _) =>
      val (newKey, newValue, newConverter) = getPasswordFields(newPassword)
      val (newKeyBytes, newValueBytes, newConverterBytes) = getPasswordFields(newPassword, rename = s => s + "_bytes", hashPw = false)
      val (currentKeyBytes, currentValueBytes, currentConverterBytes) = getPasswordFieldsCurrent(currentPassword)
      def currentUser(p: MapValue): String = p.get("name").asInstanceOf[TextValue].stringValue()
      val query =
        s"""MATCH (user:User {name: $$name})
          |WITH user, user.credentials AS oldCredentials
          |SET user.credentials = $$$newKey
          |SET user.passwordChangeRequired = false
          |RETURN oldCredentials""".stripMargin

      UpdatingSystemCommandExecutionPlan("AlterCurrentUserSetPassword", normalExecutionEngine, query,
        VirtualValues.map(Array(newKey, newKeyBytes, currentKeyBytes), Array(newValue, newValueBytes, currentValueBytes)),
        QueryHandler
          .handleError {
            case (error: HasStatus, p) if error.status() == Status.Cluster.NotALeader =>
              new DatabaseAdministrationOnFollowerException(s"User '${currentUser(p)}' failed to alter their own password: $followerError", error)
            case (error: Neo4jException, _) => error
            case (error, p) => new IllegalStateException(s"User '${currentUser(p)}' failed to alter their own password.", error)
          }
          .handleResult((_, value, p) => {
            val oldCredentials = SystemGraphCredential.deserialize(value.asInstanceOf[TextValue].stringValue(), secureHasher)
            val newValue = p.get(newKeyBytes).asInstanceOf[ByteArray].asObjectCopy()
            val currentValue = p.get(currentKeyBytes).asInstanceOf[ByteArray].asObjectCopy()
            if (!oldCredentials.matchesPassword(currentValue))
              Some(new InvalidArgumentsException(s"User '${currentUser(p)}' failed to alter their own password: Invalid principal or credentials."))
            else if (oldCredentials.matchesPassword(newValue))
              Some(new InvalidArgumentsException(s"User '${currentUser(p)}' failed to alter their own password: Old password and new password cannot be the same."))
            else
              None
          })
          .handleNoResult( p => {
            if (currentUser(p).isEmpty) // This is true if the securityContext is AUTH_DISABLED (both for community and enterprise)
              Some(new IllegalStateException("User failed to alter their own password: Command not available with auth disabled."))
            else // The 'current user' doesn't exist in the system graph
              Some(new IllegalStateException(s"User '${currentUser(p)}' failed to alter their own password: User does not exist."))
          }),
        checkCredentialsExpired = false,
        parameterGenerator = ktx => VirtualValues.map(Array("name"), Array(Values.utf8Value(ktx.securityContext().subject().username()))),
        parameterConverter = m => newConverter(newConverterBytes(currentConverterBytes(m)))
      )

    // SHOW DATABASES
    case ShowDatabases() => (_, _) =>
      val (query, generator) = makeShowDatabasesQuery()
      SystemCommandExecutionPlan("ShowDatabases", normalExecutionEngine, query, VirtualValues.EMPTY_MAP, parameterGenerator = generator)

    // SHOW DEFAULT DATABASE
    case ShowDefaultDatabase() => (_, _) =>
      val (query, generator) = makeShowDatabasesQuery(isDefault = true)
      SystemCommandExecutionPlan("ShowDefaultDatabase", normalExecutionEngine, query, VirtualValues.EMPTY_MAP, parameterGenerator = generator)

    // SHOW DATABASE foo
    case ShowDatabase(normalizedName) => (_, _) =>
      val (query, generator) = makeShowDatabasesQuery(dbName = Some(normalizedName.name))
      SystemCommandExecutionPlan("ShowDatabase", normalExecutionEngine, query,
        VirtualValues.map(Array("name"), Array(Values.utf8Value(normalizedName.name))), parameterGenerator = generator)

    case DoNothingIfNotExists(source, label, name) => (context, parameterMapping) =>
      val parameterValue = makeParameterValue(name)
      UpdatingSystemCommandExecutionPlan("DoNothingIfNotExists", normalExecutionEngine,
        s"""
           |MATCH (node:$label {name: $$name})
           |RETURN node.name AS name
        """.stripMargin, VirtualValues.map(Array("name"), Array(parameterValue)),
        QueryHandler
          .ignoreNoResult()
          .handleError {
            case (error: HasStatus, p) if error.status() == Status.Cluster.NotALeader =>
              new DatabaseAdministrationOnFollowerException(s"Failed to delete the specified ${label.toLowerCase} '${runtimeValue(name, p)}': $followerError", error)
            case (error, p) => new IllegalStateException(s"Failed to delete the specified ${label.toLowerCase} '${runtimeValue(name, p)}'.", error) // should not get here but need a default case
          },
        Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context, parameterMapping))
      )

    case DoNothingIfExists(source, label, name) => (context, parameterMapping) =>
      val parameterValue = makeParameterValue(name)
      UpdatingSystemCommandExecutionPlan("DoNothingIfExists", normalExecutionEngine,
        s"""
           |MATCH (node:$label {name: $$name})
           |RETURN node.name AS name
        """.stripMargin, VirtualValues.map(Array("name"), Array(parameterValue)),
        QueryHandler
          .ignoreOnResult()
          .handleError {
            case (error: HasStatus, p) if error.status() == Status.Cluster.NotALeader =>
              new DatabaseAdministrationOnFollowerException(s"Failed to create the specified ${label.toLowerCase} '${runtimeValue(name, p)}': $followerError", error)
            case (error, p) => new IllegalStateException(s"Failed to create the specified ${label.toLowerCase} '${runtimeValue(name, p)}'.", error) // should not get here but need a default case
          },
        Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context, parameterMapping))
      )

    // Ensure that the role or user exists before being dropped
    case EnsureNodeExists(source, label, name) => (context, parameterMapping) =>
      val parameterValue = makeParameterValue(name)
      UpdatingSystemCommandExecutionPlan("EnsureNodeExists", normalExecutionEngine,
        s"""MATCH (node:$label {name: $$name})
           |RETURN node""".stripMargin,
        VirtualValues.map(Array("name"), Array(parameterValue)),
        QueryHandler
          .handleNoResult(p => Some(new InvalidArgumentsException(s"Failed to delete the specified ${label.toLowerCase} '${runtimeValue(name, p)}': $label does not exist.")))
          .handleError {
            case (error: HasStatus, p) if error.status() == Status.Cluster.NotALeader =>
              new DatabaseAdministrationOnFollowerException(s"Failed to delete the specified ${label.toLowerCase} '${runtimeValue(name, p)}': $followerError", error)
            case (error, p) => new IllegalStateException(s"Failed to delete the specified ${label.toLowerCase} '${runtimeValue(name, p)}'.", error) // should not get here but need a default case
          },
        Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context, parameterMapping))
      )

    // SUPPORT PROCEDURES (need to be cleared before here)
    case SystemProcedureCall(_, queryString, params, checkCredentialsExpired) => (_, _) =>
      SystemCommandExecutionPlan("SystemProcedure", normalExecutionEngine, queryString, params, checkCredentialsExpired = checkCredentialsExpired)

    // Ignore the log command in community
    case LogSystemCommand(source, _) => (context, parameterMapping) =>
      fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context, parameterMapping)
  }

  private def makeShowDatabasesQuery(isDefault: Boolean = false, dbName: Option[String] = None): (String, KernelTransaction => MapValue) = {
    val defaultColumn = if (isDefault) "" else ", d.default as default"
    val paramGenerator: KernelTransaction => MapValue = ktx => generateShowAccessibleDatabasesParameter(ktx, isDefault, dbName)
    val extraFilter = (isDefault, dbName) match {
      // show default database
      case (true, _) => "AND d.default = true"
      // show database name
      case (_, Some(_)) => "AND d.name = $name"
      // show all databases
      case _ => ""
    }
    val query = s"""
       |MATCH (d: Database)
       |WHERE d.name IN $$accessibleDbs $extraFilter
       |CALL dbms.database.state(d.name) yield status, error, address, role
       |WITH d, status as currentStatus, error, address, role
       |RETURN d.name as name, address, role, d.status as requestedStatus, currentStatus, error $defaultColumn
       |ORDER BY name
    """.stripMargin
    (query, paramGenerator)
  }

  private def generateShowAccessibleDatabasesParameter(ktx: KernelTransaction, isDefault: Boolean = false, dbName: Option[String] = None ): MapValue = {
    def accessForDatabase(database: Node, roles: java.util.Set[String]): Option[Boolean] = {
      //(:Role)-[p]->(:Privilege {action: 'access'})-[s:SCOPE]->()-[f:FOR]->(d:Database)
      var result: Seq[Boolean] = Seq.empty
      database.getRelationships(Direction.INCOMING, withName("FOR")).forEach { f =>
        f.getStartNode.getRelationships(Direction.INCOMING, withName("SCOPE")).forEach { s =>
          val privilegeNode = s.getStartNode
          if (privilegeNode.getProperty("action").equals("access")) {
            privilegeNode.getRelationships(Direction.INCOMING).forEach { p =>
              val roleName = p.getStartNode.getProperty("name")
              if (roles.contains(roleName)) {
                p.getType.name() match {
                  case "DENIED" => result = result :+ false
                  case "GRANTED" => result = result :+ true
                  case _ =>
                }
              }
            }
          }
        }
      }
      result.reduceOption(_ && _)
    }

    val securityContext = ktx.securityContext()
    val allowsDatabaseManagement: Boolean =
      securityContext.allowsAdminAction(new AdminActionOnResource(PrivilegeAction.CREATE_DATABASE, DatabaseScope.ALL, Segment.ALL)) ||
      securityContext.allowsAdminAction(new AdminActionOnResource(PrivilegeAction.DROP_DATABASE, DatabaseScope.ALL, Segment.ALL))
    val transaction = ktx.internalTransaction()
    val roles = securityContext.mode().roles()

    val allDatabaseNode = transaction.findNode(Label.label("DatabaseAll"), "name", "*")
    val allDatabaseAccess = accessForDatabase(allDatabaseNode, roles)
    val defaultDatabaseNode = transaction.findNode(Label.label("DatabaseDefault"), "name", "DEFAULT")
    val defaultDatabaseAccess = if ( defaultDatabaseNode != null ) accessForDatabase(defaultDatabaseNode, roles) else None

    val accessibleDatabases = transaction.findNodes(Label.label("Database")).asScala.foldLeft[Seq[String]](Seq.empty) { (acc, dbNode) =>
      val dbName = dbNode.getProperty("name").toString
      val isDefault = Boolean.unbox(dbNode.getProperty("default"))
      if (dbName.equals(GraphDatabaseSettings.SYSTEM_DATABASE_NAME)) {
        acc :+ dbName
      } else if (allowsDatabaseManagement) {
        acc :+ dbName
      } else {
        (accessForDatabase(dbNode, roles), allDatabaseAccess, defaultDatabaseAccess, isDefault) match {
          // denied
          case (Some(false), _, _, _) => acc
          case (_, Some(false), _, _) => acc
          case (_, _, Some(false), true) => acc

          // granted
          case (Some(true), _, _, _) => acc :+ dbName
          case (_, Some(true), _, _) => acc :+ dbName
          case (_, _, Some(true), true) => acc :+ dbName

          // no privilege
          case _ => acc
        }
      }
    }

    val filteredDatabases = dbName match {
      case Some(name) => accessibleDatabases.filter(db => name.equals(db))
      case _ => accessibleDatabases
    }
    VirtualValues.map(Array("accessibleDbs"), Array(Values.stringArray(filteredDatabases: _*)))
  }

  override def isApplicableAdministrationCommand(logicalPlanState: LogicalPlanState): Boolean = {
    val logicalPlan = logicalPlanState.maybeLogicalPlan.get match {
      // Ignore the log command in community
      case LogSystemCommand(source, _) => source
      case plan => plan
    }
    logicalToExecutable.isDefinedAt(logicalPlan)
  }
}

object DatabaseStatus extends Enumeration {
  type Status = TextValue

  val Online: TextValue = Values.utf8Value("online")
  val Offline: TextValue = Values.utf8Value("offline")
}

object CommunityAdministrationCommandRuntime {
  def emptyLogicalToExecutable: PartialFunction[LogicalPlan, (RuntimeContext, ParameterMapping) => ExecutionPlan] =
    new PartialFunction[LogicalPlan, (RuntimeContext, ParameterMapping) => ExecutionPlan] {
      override def isDefinedAt(x: LogicalPlan): Boolean = false

      override def apply(v1: LogicalPlan): (RuntimeContext, ParameterMapping) => ExecutionPlan = ???
    }
}
