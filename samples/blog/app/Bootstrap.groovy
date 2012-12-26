import play.*
import play.jobs.*
import play.test.*

import models.*

@OnApplicationStart
class Bootstrap extends Job {

    public void doJob() {
        // load initial data if db is empty
        Fixtures.deleteDatabase()
        //println('Count: ' + User.count())
        Fixtures.load('initial-data.yml')
    }
}
