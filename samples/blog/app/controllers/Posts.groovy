package controllers

import play.*
import play.mvc.*

@Check('admin')
@With(Secure.class)
class Posts extends CRUD {
	
}