supervision
deathwatch
testing
conversation patterns
timeouts

create app structure
 iot collectors
 dashboards
 analysis backend
 
STEP 1
 create entry point
  - just the usual stuff
 explain supervision
  - don't do too much top level actors
 create top level supervisor
  - lifecycle hooks
  - emptyBehavior
 create device actor
 create device group actor
 create device manager
 
STEP 2
 create dashboard manager
 create dashboard actor
  - resilience to device group actor failures
  - resilience to individual device actor failures
  
STEP 3
 create analytics manager
 set up aggregator
 set up pool of workers
 hook analytics up with dashboard (show global average)
 make it resilient to analytics failure
 
STEP 4
 set up configuration
 set up logging
 
  