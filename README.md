# AdServe: Video Ad Server

AdServe is a video ad server in Java 21 and Spring Boot. Given one ad break (viewer, title,
break length, device, region), it evaluates every campaign's targeting, enforces policy and
frequency caps, paces each campaign's daily budget, fills the break with a pod of creatives
(which ads, in which order, never two car ads back to back), signs a token for every
impression, and logs the decision to Kafka without a database write on the request path.
**Traffic is replayed from real ad logs, and the viewers are simulated.** The requests are the
1,745,722 impression rows of one day of the public iPinYou RTB dataset (season 2, 2013-06-11), and the
campaigns are that day's 55 real creatives from 5 advertisers, each with a daily budget equal to
what it actually paid that day and a click rate taken from the click logs. No one watched
anything. Where the log has no field for something a video ad server needs (break length,
creative duration, a TV device class, title genre), a deterministic rule fills it in, and
`DESIGN.md` lists every such rule. Every latency figure comes from one laptop, with the load
generator running on that same machine. None of them is a production-scale claim.

Status: under construction. Milestones and measured numbers are in `NUMBERS.md` as they land.
