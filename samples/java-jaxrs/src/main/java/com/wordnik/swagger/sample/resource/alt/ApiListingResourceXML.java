package com.wordnik.swagger.sample.resource.alt;

import com.wordnik.swagger.annotations.*;
import com.wordnik.swagger.jaxrs.*;

import javax.ws.rs.*;

@Path("/api-docs.xml")
@Api("/api-docs")
@Produces({"application/xml"})
class ApiListingResourceXML extends AlternateApiListing {}