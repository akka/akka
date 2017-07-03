# Cluster Team

@@@ note

Cluster teams are a work-in-progress feature, and behavior is still expected to change.

@@@

Teams are used to define islands of the cluster that are colocated.
They can be used to make the cluster "dc-aware", run the cluster in multiple availability zones or regions.

Cluster nodes can be assigned to a team by setting the `akka.cluster.team` setting.
When no team is specified, a node will belong to the 'default' team.

The team is added to the list of roles of the node with the prefix "team-".