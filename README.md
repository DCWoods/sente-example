# Modified Sente reference example

> [Sente](https://github.com/ptaoussanis/sente) is a websockets library for clojure. This is a modified version of the reference project that comes with sente. The project has been modifed slightly to run in an uberjar, and then in a docker container. Then some additional files have been added to enable running on AWS Elastic Beanstalk, in a mulit-container docker configuration, with the sente-example app running in one container, behind an Nginx reverse proxy in a second docker container.

> These instruction describe the modifications that were made to the example project, and describe how to get the project running on AWS. They are written from the perspective of a mac user.


## Create uberjar

First we need to make a few changes to get the project running as an uberjar.

To the `project.clj` file:

* add `:aot :all`
* add `:hooks [leiningen.cljsbuild]` so that the clojurescript is compiled when the uberjar is built
* comment the austin plugin to avoid errors `;[com.cemerick/austin "0.1.6"]

To the `src/example/server.clj` file:

* add `(:gen-class :main true)` to the top of the namespace declaration

After these modifications we can test that the uberjar is successful. From within the example project folder:

    lein clean
    lein uberjar
    java -jar /target/sente-1.8.1-standalone.jar

This should start the sente project with the default http-kit webserver, and launch a web brower with the example project page. Confirm that websockets are working locally.


## Running from a Docker container

This assumes that Docker is installed and the Docker daemon is running.

First we make another modification to `server.clj` so that instead of launching on a random port, the application always uses port 8080. Modify the `start!` function to use port 8080:

    (defn start! [] (start-router!) (start-web-server! 8080) (start-example-broadcaster!))

Next create a simple file in the example project root called `Dockerfile`:

    FROM java:8

    ADD target/sente-1.8.1-standalone.jar /srv/sente-example-app.jar

    EXPOSE 8080

    CMD ["java", "-jar", "/srv/sente-example-app.jar"]

Now we can build the docker container with the name `kittynz/sente-example`. The first part of this should be your Dockerhub username. First we delete any existing image:

    docker rmi -f kittynz/sente-example

Then build the docker container (don't forget the period at the end):

    docker build -t kittynz/sente-example .

Now we can run the docker container, which should launch our webserver:

    docker run -p 8080:8080 kittynz/sente-example

Because the docker container is running in a virtual host, it isn't able to launch a browser locally. To check that the example project is working, we need the IP address of the host. Run `arp -a` to get a listing of the available IP addresses, and the docker host will be something like `192.168.99.100`. The `-p 8080:8080` part of the run command maps port 8080 on the container to that on the host. Now we can check the app is working at `http://192.168.99.100:8080`.

At this point go to docker hub and create a public repository called `sente-example` in your profile. Then we can push the docker container to the public repository so it is available to AWS.

    docker push kittynz/sente-example

If you haven't authenticated previously it may prompt you for credentials.


## Running on AWS Elastic Beanstalk

First you need an AWS account. You can sign up for a year of the free tier for free, which is all you need for this example. In this we use the Sydney region (ap-southeast-2).

We will be running on Elastic Beanstalk, which takes care of most of the details for us. First we need to create some more files in our project.

Create a file `.ebextensions/custom.config`:

    option_settings:
      - namespace:  aws:autoscaling:asg
        option_name:  MaxSize
        value:  1
      - namespace:  aws:elb:loadbalancer
        option_name:  CrossZone
        value:  false
      - namespace:  aws:elb:listener:80
        option_name:  ListenerProtocol
        value:  TCP
      - namespace:  aws:elb:policies:EnableProxyProtocol
        option_name:  InstancePorts
        value:  80
      - namespace:  aws:elb:policies:EnableProxyProtocol
        option_name:  ProxyProtocol
        value:  true

This file instructs Elastic Beanstalk to customise the configuration as it creates our environment. We ensure that only one server instance is created, disable cross zone load balancing, switch the load balancer to use TCP protocol instead of HTTP, and enable proxy protocol on the load balancer, which is required for websockets, otherwise the request looks like it comes from the load balancer instead of the client, and the client details are stripped.

Next we create the `Dockerfile.aws.json` file that is needed to specify the multi-container docker environment that Elastic Beanstalk creates:

    {
      "AWSEBDockerrunVersion": 2,
      "volumes": [
        {
          "name": "nginx-proxy-conf",
          "host": {
            "sourcePath": "/var/app/current/proxy/conf.d"
          }
        }
      ],
      "containerDefinitions": [
        {
          "name": "sente-example-app",
          "image": "kittynz/sente-example",
          "essential": true,
          "memory": 256,
          "environment": [

          ],
          "portMappings": [
            {
              "hostPort": 8080,
              "containerPort": 8080
            }
          ]
        },
        {
          "name": "nginx-proxy",
          "image": "nginx",
          "essential": true,
          "memory": 128,
          "portMappings": [
            {
              "hostPort": 80,
              "containerPort": 80
            }
          ],
          "links": [
            "sente-example-app"
          ],
          "mountPoints": [
            {
              "sourceVolume": "nginx-proxy-conf",
              "containerPath": "/etc/nginx/conf.d",
              "readOnly": true
            },
            {
              "sourceVolume": "awseb-logs-nginx-proxy",
              "containerPath": "/var/log/nginx"
            }
          ]
        }
      ]
    }

This file defines two docker containers, one for the app and one for the Nginx reverse proxy. It sets the appropriate port mappings and tells nginx to use our custom config file, which we now create as `proxy/conf.d/custom.conf`:

    log_format elb_log '$proxy_protocol_addr - $remote_user [$time_local] ' '"$request" $status $body_bytes_sent "$http_referer" ' - '"$request_body_file"' - '"ReqUpgrade: $http_upgrade" "ReqConnection: $http_connection"' - '"UpstreamUpgrade: $upstream_http_upgrade" "UpstreamConnection: $upstream_http_connection"' - '"SentUpgrade: $sent_http_upgrade" "SentConnection: $sent_http_connection"';

    upstream example_backend {
        server sente-example-app:8080;
    }

    map $http_sec_websocket_version $proxy_connection {
            "~\d+" Upgrade;
            default $http_connection;
    }

    map $http_sec_websocket_version $proxy_upgrade {
            "~\d+" websocket;
            default $http_upgrade;
    }

    map $upstream_http_upgrade $sent_connection {
            websocket Upgrade;
            default $sent_http_upgrade;
    }


    server {
      listen 80 proxy_protocol;
      server_name sente-example-env.ap-southeast-2.elasticbeanstalk.com;

      set_real_ip_from 0.0.0.0/0;
      real_ip_header   proxy_protocol;

      access_log  /var/log/nginx/access.log  elb_log;

      location / {
        proxy_pass http://example_backend;
        proxy_http_version 1.1;
        proxy_set_header  Upgrade            $proxy_upgrade;
        proxy_set_header  Connection         $proxy_connection;
        proxy_set_header  X-Real-IP          $proxy_protocol_addr;
        proxy_set_header  X-Forwarded-For    $proxy_protocol_addr;
        proxy_set_header  Host               $http_host;
      }
    }

This is our best attempt at the appropriate nginx config to get websockets working behind Elastic Beanstalk using proxy protocol.

Next we need to zip all the required files into a zip archive to upload to AWS. To assist with this we add the following to the `project.clj` file:

    :zip ["Dockerrun.aws.json" "proxy/" ".ebextensions/"]

And also add the following to the plugins list in the same file:

    [lein-zip "0.1.1"]

From the example project root run:

    lein zip

Which will create the file `target/sente-1.8.1.zip`.

Now, go to the Elastic Beanstalk admin page and create a new project, and use the following environment settings:

* Create "web server" type environment
* Use the predefined configuration: multi-container docker, and use the default "load balancing, auto scaling" type
* Upload our own source, using the zip file we created above
* Choose an environment name. This must be unique within a region. For example `sente-example-env`. This gives a url like `sente-example-env.ap-southeast-2.elasticbeanstalk.com`. Note that this must match the `server_name` directive in the `custom.conf` file, so if AWS says it isn't unique this file must be updated to match the chosen URL.
* Instance type: t2.micro. This is free on the free tier, and very cheap otherwise.
* EC2 key pair. This allows using ssh to connect to the server, which is useful for examining logs. One can be created in the EC2 section of the admin interface and then selected here.
* Turn off cross-zone load balancing.
* Select Health reporting: Basic
* On the next page select the default instance profile and service role. If these don't exist, go back and create a default Elastic Beanstalk project from the main admin page, and then they should be available here.
* Review and launch

The environment will take a few minutes to be created, and then the sente-example app should be available at the URL chosen: [http://sente-example-env.ap-southeast-2.elasticbeanstalk.com](http://sente-example-env.ap-southeast-2.elasticbeanstalk.com/).

You can reload the page and it randomly selects an ajax connection or a websockets connection. Note that with the current setup the ajax connection works fine, but the websockets connection does not work, for an unknown reason.
