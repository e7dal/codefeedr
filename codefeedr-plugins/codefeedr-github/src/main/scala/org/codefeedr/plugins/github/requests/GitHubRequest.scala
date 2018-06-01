/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.codefeedr.plugins.github.requests

import java.net.SocketTimeoutException

import org.apache.logging.log4j.scala.Logging
import org.codefeedr.plugins.github.GitHubEndpoints

import scalaj.http.{Http, HttpRequest, HttpResponse}

/**
  * Exception thrown when something goes wrong during a request to the GitHub API.
  *
  * @param message the exception message.
  * @param cause   the exception cause.
  */
final case class GitHubRequestException(private val message: String = "",
                                        private val cause: Throwable = None.orNull)
  extends Exception(message, cause)

/**
  * Handles a GitHubRequest.
  *
  * @param endpoint       the request endpoint.
  * @param requestHeaders the request headers.
  */
class GitHubRequest(endpoint: String, requestHeaders: List[Header]) extends Logging {

  //default accept header
  val ACCEPT_HEADER = ("Accept", "application/vnd.github.v3+json")

  def TIMEOUT_CAP = 32

  /**
    * Request the data and parse the response.
    *
    * @return a GitHubResponse object.
    */
  def request(): GitHubResponse = {
    val request = retrieveResponse(buildRequest(), 1)
    val response = parseResponse(request)

    //handle invalid status codes
    handleErrorCodes(response)

    response
  }

  @throws(classOf[Exception])
  def retrieveResponse(request: HttpRequest, timeOut: Int): HttpResponse[String] = {
    try {
      return request.asString
    } catch {
      case x: Exception if (x.getClass != classOf[GitHubRequestException]) =>
      {
        if (timeOut >= TIMEOUT_CAP) throw new GitHubRequestException(s"Requests timed out after $TIMEOUT_CAP seconds.")

        println(s"Failed doing a request retrying in ${timeOut * 2} in seconds.")
        Thread.sleep(timeOut * 2 * 1000)
        return retrieveResponse(request, timeOut * 2)
      }
    }
  }

  /**
    * Handle error codes.
    * Forwards only 200 and 304 status codes, otherwise it throws and exception.
    *
    * @param response the GitHub response to handle.
    */
  def handleErrorCodes(response: GitHubResponse): GitHubResponse = response.status match {
    case 200 | 304 => response
    case other => throw new GitHubRequestException(s"Undefined response code $other. Body: ${response.body} Endpoint: $endpoint")
  }

  /**
    * Parses the HttpResponse into a GitHubResponse.
    *
    * @param response the HttpResponse to parse.
    * @return a GitHubResponse.
    */
  def parseResponse(response: HttpResponse[String]): GitHubResponse = {
    val responseHeaders = response
      .headers
      .map(x => Header(x._1, x._2.toArray))
      .toList

    GitHubResponse(response.body, response.code, responseHeaders)
  }


  /**
    * Build an URL using predefined headers.
    *
    * @return the HttpRequest
    */
  def buildRequest(): HttpRequest = {
    val headers = requestHeaders
      .map(h => (h.key, h.value.reduce(_ + "," + _)))
      .toMap + ACCEPT_HEADER

    val http = Http(GitHubEndpoints.DEFAULT_URL + endpoint)
      .timeout(connTimeoutMs = 10000, readTimeoutMs = 15000)
      .headers(headers)

    http
  }


}
