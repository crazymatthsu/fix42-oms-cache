# fix42-oms-cache

[goal]
- provide a cache API to process FIX 4.2 audit streams / drop copy messages.  
- The cache API will process below messages types, and provide a cache for the latest state of: each order.
  - 35=D (New) Order - Single)
  - 35=8 (Execution Report)
  - 35=9 (Order Cancel Reject)
  - 35=F (Order Cancel Request)
  - 35=G (Order Cancel/Replace Request)
  - 35=H (Order Status Request)
  - 35=Q (Don't Know Trade)
- A parent order can have multiple child orders, and the cache will maintain the latest state of each child order as well, and also the parent order's latest state. 
- child order will have parent orderID, and the cache will maintain a mapping of parent orderID to child orderIDs.
- analyze FIX 4.2 ClOrdID, OrderID, ExecID and other ID tags, how to link different FIX messages together to maintain the latest state of each order.

[data type analysis of cache api]
- should the cache API use protobuf objects or more generic data structures like Map<String, Object> to store the order state?
- should the cache API take json as the value ? if so, how to update json value directly ? 

[3rd party cache library or build from scratch]
- should we use 3rd party cache library, or build from scratch?

[store all original FIX messages in cache and join all FIX messages for a given orderID]
- should we store all original FIX messages in cache, and join all FIX messages for a given orderID

[FIX 4.2 to protobuf, and protobuf to FIX 4.2]
- based on FIX 4.2 tag dictionary, create corresponding .proto files, and generate java classes for the .proto file. 
- the FIX 4.2 specs should support repeating groups. 
- provide a FIX parser API so that the string FIX messages will be parsed into protobuf objects 
- the protobuf objects can also be transformed back to FIX string messages.

[latest state of each order]
- the cache API will provide a method to get the latest state of each order
- design the fields of the latest state of each order, and how to update the latest state based on different FIX messages.

[cache api] 
- api will provide methods to process each FIX message type, and update the cache accordingly.
- for example, it will have methods like processNewOrderSingle, processExecutionReport, processOrderCancelReject, etc.
- API can search for the latest state of an order by Account, Symbol, ClOrdID, OrderID, or ExecID.

[AMPS]
- should I design the upstream pipeline to be 
  - plan A:
    - FIX engine -> AMPS with transaction log enabled -> OMS cache API client build the latest state cache -> publish latest state cache to AMPS sow topic
  - plan B: 
    - FIX engine -> Kafka -> OMS cache API client build the latest state cache -> publish latest state cache to AMPS sow topic
