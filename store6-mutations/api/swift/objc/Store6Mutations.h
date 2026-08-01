#import <Foundation/NSArray.h>
#import <Foundation/NSDictionary.h>
#import <Foundation/NSError.h>
#import <Foundation/NSObject.h>
#import <Foundation/NSSet.h>
#import <Foundation/NSString.h>
#import <Foundation/NSValue.h>

@class Store6MutationsDeadLetter, Store6MutationsKotlinArray<T>, Store6MutationsKotlinByteArray, Store6MutationsKotlinByteIterator, Store6MutationsKotlinEnum<E>, Store6MutationsKotlinEnumCompanion, Store6MutationsKotlinException, Store6MutationsKotlinIllegalStateException, Store6MutationsKotlinRuntimeException, Store6MutationsKotlinThrowable, Store6MutationsKotlinUnit, Store6MutationsMutationConflictBuilder<K, V>, Store6MutationsMutationConflictResolutionServerWins, Store6MutationsMutationFailure, Store6MutationsMutationFailureKind, Store6MutationsMutationKeyIdentity, Store6MutationsMutationPendingState, Store6MutationsMutationPreconditionCandidate<K, V>, Store6MutationsMutationPresenceAbsent, Store6MutationsMutationPresenceState, Store6MutationsMutationPush<K, V>, Store6MutationsMutationRetirement, Store6MutationsMutationRetirementAck, Store6MutationsMutationStore<K, V>, Store6MutationsMutationStoreBuilder<K, V>, Store6MutationsMutatorRef<K, V, A>, Store6MutationsMutatorRegistry<K, V>, Store6MutationsMutatorRegistryBuilder<K, V>, Store6MutationsPendingIntent, Store6MutationsStaleSet<K>, Store6MutationsStore6_coreFreshnessContext, Store6MutationsStore6_coreKeyStatus, Store6MutationsStore6_coreOrigin, Store6MutationsStore6_coreStoreError, Store6MutationsStore6_coreStoreNamespace;

@protocol Store6MutationsKotlinComparable, Store6MutationsKotlinFunction, Store6MutationsKotlinIterator, Store6MutationsKotlinSuspendFunction1, Store6MutationsKotlinx_coroutines_coreFlow, Store6MutationsKotlinx_coroutines_coreFlowCollector, Store6MutationsKotlinx_coroutines_coreSharedFlow, Store6MutationsMutationAck, Store6MutationsMutationCodec, Store6MutationsMutationConflictResolution, Store6MutationsMutationEvent, Store6MutationsMutationIntentEvent, Store6MutationsMutationKeyResolver, Store6MutationsMutationPresence, Store6MutationsMutationServer, Store6MutationsStore6_coreBookkeeper, Store6MutationsStore6_coreFetchPlan, Store6MutationsStore6_coreFetcher, Store6MutationsStore6_coreFetcherResult, Store6MutationsStore6_coreFreshness, Store6MutationsStore6_coreFreshnessValidator, Store6MutationsStore6_coreSourceOfTruth, Store6MutationsStore6_coreStore, Store6MutationsStore6_coreStoreKey, Store6MutationsStore6_coreStoreMeta, Store6MutationsStore6_coreStoreTelemetry, Store6MutationsStore6_coreWallClock;

NS_ASSUME_NONNULL_BEGIN
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunknown-warning-option"
#pragma clang diagnostic ignored "-Wincompatible-property-type"
#pragma clang diagnostic ignored "-Wnullability"

#pragma push_macro("_Nullable_result")
#if !__has_feature(nullability_nullable_result)
#undef _Nullable_result
#define _Nullable_result _Nullable
#endif

__attribute__((swift_name("KotlinBase")))
@interface Store6MutationsBase : NSObject
- (instancetype)init __attribute__((unavailable));
+ (instancetype)new __attribute__((unavailable));
+ (void)initialize __attribute__((objc_requires_super));
@end

@interface Store6MutationsBase (Store6MutationsBaseCopying) <NSCopying>
@end

__attribute__((swift_name("KotlinMutableSet")))
@interface Store6MutationsMutableSet<ObjectType> : NSMutableSet<ObjectType>
@end

__attribute__((swift_name("KotlinMutableDictionary")))
@interface Store6MutationsMutableDictionary<KeyType, ObjectType> : NSMutableDictionary<KeyType, ObjectType>
@end

@interface NSError (NSErrorStore6MutationsKotlinException)
@property (readonly) id _Nullable kotlinException;
@end

__attribute__((swift_name("KotlinNumber")))
@interface Store6MutationsNumber : NSNumber
- (instancetype)initWithChar:(char)value __attribute__((unavailable));
- (instancetype)initWithUnsignedChar:(unsigned char)value __attribute__((unavailable));
- (instancetype)initWithShort:(short)value __attribute__((unavailable));
- (instancetype)initWithUnsignedShort:(unsigned short)value __attribute__((unavailable));
- (instancetype)initWithInt:(int)value __attribute__((unavailable));
- (instancetype)initWithUnsignedInt:(unsigned int)value __attribute__((unavailable));
- (instancetype)initWithLong:(long)value __attribute__((unavailable));
- (instancetype)initWithUnsignedLong:(unsigned long)value __attribute__((unavailable));
- (instancetype)initWithLongLong:(long long)value __attribute__((unavailable));
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value __attribute__((unavailable));
- (instancetype)initWithFloat:(float)value __attribute__((unavailable));
- (instancetype)initWithDouble:(double)value __attribute__((unavailable));
- (instancetype)initWithBool:(BOOL)value __attribute__((unavailable));
- (instancetype)initWithInteger:(NSInteger)value __attribute__((unavailable));
- (instancetype)initWithUnsignedInteger:(NSUInteger)value __attribute__((unavailable));
+ (instancetype)numberWithChar:(char)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedChar:(unsigned char)value __attribute__((unavailable));
+ (instancetype)numberWithShort:(short)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedShort:(unsigned short)value __attribute__((unavailable));
+ (instancetype)numberWithInt:(int)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedInt:(unsigned int)value __attribute__((unavailable));
+ (instancetype)numberWithLong:(long)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedLong:(unsigned long)value __attribute__((unavailable));
+ (instancetype)numberWithLongLong:(long long)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value __attribute__((unavailable));
+ (instancetype)numberWithFloat:(float)value __attribute__((unavailable));
+ (instancetype)numberWithDouble:(double)value __attribute__((unavailable));
+ (instancetype)numberWithBool:(BOOL)value __attribute__((unavailable));
+ (instancetype)numberWithInteger:(NSInteger)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedInteger:(NSUInteger)value __attribute__((unavailable));
@end

__attribute__((swift_name("KotlinByte")))
@interface Store6MutationsByte : Store6MutationsNumber
- (instancetype)initWithChar:(char)value;
+ (instancetype)numberWithChar:(char)value;
@end

__attribute__((swift_name("KotlinUByte")))
@interface Store6MutationsUByte : Store6MutationsNumber
- (instancetype)initWithUnsignedChar:(unsigned char)value;
+ (instancetype)numberWithUnsignedChar:(unsigned char)value;
@end

__attribute__((swift_name("KotlinShort")))
@interface Store6MutationsShort : Store6MutationsNumber
- (instancetype)initWithShort:(short)value;
+ (instancetype)numberWithShort:(short)value;
@end

__attribute__((swift_name("KotlinUShort")))
@interface Store6MutationsUShort : Store6MutationsNumber
- (instancetype)initWithUnsignedShort:(unsigned short)value;
+ (instancetype)numberWithUnsignedShort:(unsigned short)value;
@end

__attribute__((swift_name("KotlinInt")))
@interface Store6MutationsInt : Store6MutationsNumber
- (instancetype)initWithInt:(int)value;
+ (instancetype)numberWithInt:(int)value;
@end

__attribute__((swift_name("KotlinUInt")))
@interface Store6MutationsUInt : Store6MutationsNumber
- (instancetype)initWithUnsignedInt:(unsigned int)value;
+ (instancetype)numberWithUnsignedInt:(unsigned int)value;
@end

__attribute__((swift_name("KotlinLong")))
@interface Store6MutationsLong : Store6MutationsNumber
- (instancetype)initWithLongLong:(long long)value;
+ (instancetype)numberWithLongLong:(long long)value;
@end

__attribute__((swift_name("KotlinULong")))
@interface Store6MutationsULong : Store6MutationsNumber
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value;
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value;
@end

__attribute__((swift_name("KotlinFloat")))
@interface Store6MutationsFloat : Store6MutationsNumber
- (instancetype)initWithFloat:(float)value;
+ (instancetype)numberWithFloat:(float)value;
@end

__attribute__((swift_name("KotlinDouble")))
@interface Store6MutationsDouble : Store6MutationsNumber
- (instancetype)initWithDouble:(double)value;
+ (instancetype)numberWithDouble:(double)value;
@end

__attribute__((swift_name("KotlinBoolean")))
@interface Store6MutationsBoolean : Store6MutationsNumber
- (instancetype)initWithBool:(BOOL)value;
+ (instancetype)numberWithBool:(BOOL)value;
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DeadLetter")))
@interface Store6MutationsDeadLetter : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t attempts __attribute__((swift_name("attempts")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *canonicalId __attribute__((swift_name("canonicalId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationFailure *failure __attribute__((swift_name("failure")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutatorId __attribute__((swift_name("mutatorId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly, getter=namespace) NSString *namespace_ __attribute__((swift_name("namespace_")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t parkedAtEpochMillis __attribute__((swift_name("parkedAtEpochMillis")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationAck")))
@protocol Store6MutationsMutationAck
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAbsentAck")))
@interface Store6MutationsMutationAbsentAck<K, V> : Store6MutationsBase <Store6MutationsMutationAck>
- (instancetype)initWithEtag:(NSString * _Nullable)etag __attribute__((swift_name("init(etag:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationEvent")))
@protocol Store6MutationsMutationEvent
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationIntentEvent")))
@protocol Store6MutationsMutationIntentEvent <Store6MutationsMutationEvent>
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAcknowledged")))
@interface Store6MutationsMutationAcknowledged : Store6MutationsBase <Store6MutationsMutationIntentEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationPresenceState *presence __attribute__((swift_name("presence")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAdopted")))
@interface Store6MutationsMutationAdopted : Store6MutationsBase <Store6MutationsMutationIntentEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationPresenceState *presence __attribute__((swift_name("presence")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAttempted")))
@interface Store6MutationsMutationAttempted : Store6MutationsBase <Store6MutationsMutationIntentEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t attempt __attribute__((swift_name("attempt")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationCheckpointConfirmed")))
@interface Store6MutationsMutationCheckpointConfirmed : Store6MutationsBase <Store6MutationsMutationEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t confirmedThroughSequence __attribute__((swift_name("confirmedThroughSequence")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t requestedThroughSequence __attribute__((swift_name("requestedThroughSequence")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationCheckpointFailed")))
@interface Store6MutationsMutationCheckpointFailed : Store6MutationsBase <Store6MutationsMutationEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationFailure *failure __attribute__((swift_name("failure")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t requestedThroughSequence __attribute__((swift_name("requestedThroughSequence")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationCodec")))
@protocol Store6MutationsMutationCodec
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (id)decodeVersion:(int32_t)version bytes:(Store6MutationsKotlinByteArray *)bytes __attribute__((swift_name("decode(version:bytes:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (Store6MutationsKotlinByteArray *)encodeValue:(id)value __attribute__((swift_name("encode(value:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationConflictBuilder")))
@interface Store6MutationsMutationConflictBuilder<K, V> : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)mergeMerge:(id<Store6MutationsMutationConflictResolution> (^)(id<Store6MutationsMutationPresence>, id<Store6MutationsMutationPresence>, id<Store6MutationsMutationPresence>))merge __attribute__((swift_name("merge(merge:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)preconditionSelect:(id<Store6MutationsStore6_coreStoreMeta> _Nullable (^)(Store6MutationsMutationPreconditionCandidate<K, V> *))select __attribute__((swift_name("precondition(select:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationConflictObserved")))
@interface Store6MutationsMutationConflictObserved : Store6MutationsBase <Store6MutationsMutationIntentEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsStore6_coreStoreMeta> _Nullable serverMeta __attribute__((swift_name("serverMeta")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationConflictResolution")))
@protocol Store6MutationsMutationConflictResolution
@required
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationConflictResolutionRetry")))
@interface Store6MutationsMutationConflictResolutionRetry<V> : Store6MutationsBase <Store6MutationsMutationConflictResolution>
- (instancetype)initWithValue:(id<Store6MutationsMutationPresence>)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsMutationPresence> value __attribute__((swift_name("value")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationConflictResolutionServerWins")))
@interface Store6MutationsMutationConflictResolutionServerWins : Store6MutationsBase <Store6MutationsMutationConflictResolution>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)serverWins __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6MutationsMutationConflictResolutionServerWins *shared __attribute__((swift_name("shared")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationEffectApplied")))
@interface Store6MutationsMutationEffectApplied : Store6MutationsBase <Store6MutationsMutationIntentEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t effectIndex __attribute__((swift_name("effectIndex")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationEffectSkipped")))
@interface Store6MutationsMutationEffectSkipped : Store6MutationsBase <Store6MutationsMutationIntentEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t effectIndex __attribute__((swift_name("effectIndex")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationEnqueued")))
@interface Store6MutationsMutationEnqueued : Store6MutationsBase <Store6MutationsMutationIntentEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t clientSequence __attribute__((swift_name("clientSequence")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutatorId __attribute__((swift_name("mutatorId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationFailed")))
@interface Store6MutationsMutationFailed : Store6MutationsBase <Store6MutationsMutationIntentEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationFailure *failure __attribute__((swift_name("failure")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationPendingState *state __attribute__((swift_name("state")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationFailure")))
@interface Store6MutationsMutationFailure : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *detail __attribute__((swift_name("detail")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationFailureKind *kind __attribute__((swift_name("kind")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *message __attribute__((swift_name("message")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@end

__attribute__((swift_name("KotlinComparable")))
@protocol Store6MutationsKotlinComparable
@required
- (int32_t)compareToOther:(id _Nullable)other __attribute__((swift_name("compareTo(other:)")));
@end

__attribute__((swift_name("KotlinEnum")))
@interface Store6MutationsKotlinEnum<E> : Store6MutationsBase <Store6MutationsKotlinComparable>
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) Store6MutationsKotlinEnumCompanion *companion __attribute__((swift_name("companion")));
- (int32_t)compareToOther:(E)other __attribute__((swift_name("compareTo(other:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *name __attribute__((swift_name("name")));
@property (readonly) int32_t ordinal __attribute__((swift_name("ordinal")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationFailureKind")))
@interface Store6MutationsMutationFailureKind : Store6MutationsKotlinEnum<Store6MutationsMutationFailureKind *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) Store6MutationsMutationFailureKind *identity __attribute__((swift_name("identity")));
@property (class, readonly) Store6MutationsMutationFailureKind *codec __attribute__((swift_name("codec")));
@property (class, readonly) Store6MutationsMutationFailureKind *projection __attribute__((swift_name("projection")));
@property (class, readonly) Store6MutationsMutationFailureKind *protocol __attribute__((swift_name("protocol")));
@property (class, readonly) Store6MutationsMutationFailureKind *conflict __attribute__((swift_name("conflict")));
@property (class, readonly) Store6MutationsMutationFailureKind *transport __attribute__((swift_name("transport")));
@property (class, readonly) Store6MutationsMutationFailureKind *adoption __attribute__((swift_name("adoption")));
@property (class, readonly) Store6MutationsMutationFailureKind *effect __attribute__((swift_name("effect")));
@property (class, readonly) Store6MutationsMutationFailureKind *persistence __attribute__((swift_name("persistence")));
+ (Store6MutationsKotlinArray<Store6MutationsMutationFailureKind *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<Store6MutationsMutationFailureKind *> *entries __attribute__((swift_name("entries")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationKeyIdentity")))
@interface Store6MutationsMutationKeyIdentity : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *canonicalId __attribute__((swift_name("canonicalId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly, getter=namespace) NSString *namespace_ __attribute__((swift_name("namespace_")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationKeyResolver")))
@protocol Store6MutationsMutationKeyResolver
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)resolveIdentity:(Store6MutationsMutationKeyIdentity *)identity completionHandler:(void (^)(id<Store6MutationsStore6_coreStoreKey> _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("resolve(identity:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationParked")))
@interface Store6MutationsMutationParked : Store6MutationsBase <Store6MutationsMutationIntentEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationFailure *failure __attribute__((swift_name("failure")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPendingState")))
@interface Store6MutationsMutationPendingState : Store6MutationsKotlinEnum<Store6MutationsMutationPendingState *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) Store6MutationsMutationPendingState *pending __attribute__((swift_name("pending")));
@property (class, readonly) Store6MutationsMutationPendingState *inflight __attribute__((swift_name("inflight")));
@property (class, readonly) Store6MutationsMutationPendingState *refreshing __attribute__((swift_name("refreshing")));
@property (class, readonly) Store6MutationsMutationPendingState *adopting __attribute__((swift_name("adopting")));
@property (class, readonly) Store6MutationsMutationPendingState *applyingEffects __attribute__((swift_name("applyingEffects")));
+ (Store6MutationsKotlinArray<Store6MutationsMutationPendingState *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<Store6MutationsMutationPendingState *> *entries __attribute__((swift_name("entries")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPreconditionCandidate")))
@interface Store6MutationsMutationPreconditionCandidate<K, V> : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsMutationPresence> base __attribute__((swift_name("base")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsStore6_coreStoreMeta> _Nullable capturedMeta __attribute__((swift_name("capturedMeta")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) K key __attribute__((swift_name("key")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsMutationPresence> mine __attribute__((swift_name("mine")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationPresence")))
@protocol Store6MutationsMutationPresence
@required
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPresenceAbsent")))
@interface Store6MutationsMutationPresenceAbsent : Store6MutationsBase <Store6MutationsMutationPresence>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)absent __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6MutationsMutationPresenceAbsent *shared __attribute__((swift_name("shared")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPresencePresent")))
@interface Store6MutationsMutationPresencePresent<V> : Store6MutationsBase <Store6MutationsMutationPresence>
- (instancetype)initWithValue:(V)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) V value __attribute__((swift_name("value")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPresenceState")))
@interface Store6MutationsMutationPresenceState : Store6MutationsKotlinEnum<Store6MutationsMutationPresenceState *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) Store6MutationsMutationPresenceState *present __attribute__((swift_name("present")));
@property (class, readonly) Store6MutationsMutationPresenceState *absent __attribute__((swift_name("absent")));
+ (Store6MutationsKotlinArray<Store6MutationsMutationPresenceState *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<Store6MutationsMutationPresenceState *> *entries __attribute__((swift_name("entries")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPresentAck")))
@interface Store6MutationsMutationPresentAck<K, V> : Store6MutationsBase <Store6MutationsMutationAck>
- (instancetype)initWithAuthoritative:(V)authoritative etag:(NSString * _Nullable)etag canonicalKey:(K _Nullable)canonicalKey __attribute__((swift_name("init(authoritative:etag:canonicalKey:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) V authoritative __attribute__((swift_name("authoritative")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) K _Nullable canonicalKey __attribute__((swift_name("canonicalKey")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPush")))
@interface Store6MutationsMutationPush<K, V> : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsMutationPresence> base __attribute__((swift_name("base")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsStore6_coreStoreMeta> _Nullable baseMeta __attribute__((swift_name("baseMeta")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t clientSequence __attribute__((swift_name("clientSequence")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *idempotencyKey __attribute__((swift_name("idempotencyKey")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) K key __attribute__((swift_name("key")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsMutationPresence> mine __attribute__((swift_name("mine")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t retiredThroughSequence __attribute__((swift_name("retiredThroughSequence")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t valueCodecVersion __attribute__((swift_name("valueCodecVersion")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationRetired")))
@interface Store6MutationsMutationRetired : Store6MutationsBase <Store6MutationsMutationIntentEvent>

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t generation __attribute__((swift_name("generation")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t retiredThroughSequence __attribute__((swift_name("retiredThroughSequence")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationRetirement")))
@interface Store6MutationsMutationRetirement : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t retiredThroughSequence __attribute__((swift_name("retiredThroughSequence")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationRetirementAck")))
@interface Store6MutationsMutationRetirementAck : Store6MutationsBase
- (instancetype)initWithConfirmedThroughSequence:(int64_t)confirmedThroughSequence __attribute__((swift_name("init(confirmedThroughSequence:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t confirmedThroughSequence __attribute__((swift_name("confirmedThroughSequence")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationServer")))
@protocol Store6MutationsMutationServer
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)pushRequest:(Store6MutationsMutationPush<id<Store6MutationsStore6_coreStoreKey>, id> *)request completionHandler:(void (^)(id<Store6MutationsMutationAck> _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("push(request:completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)retireRequest:(Store6MutationsMutationRetirement *)request completionHandler:(void (^)(Store6MutationsMutationRetirementAck * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("retire(request:completionHandler:)")));
@end


/**
 * @note annotations
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreStore")))
@protocol Store6MutationsStore6_coreStore
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearKey:(id<Store6MutationsStore6_coreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clear(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearNamespaceNamespace:(Store6MutationsStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearNamespace(namespace:completionHandler:)")));
- (void)close __attribute__((swift_name("close()")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)getKey:(id<Store6MutationsStore6_coreStoreKey>)key freshness:(id<Store6MutationsStore6_coreFreshness>)freshness completionHandler:(void (^)(id _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("get(key:freshness:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateKey:(id<Store6MutationsStore6_coreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidate(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateNamespaceNamespace:(Store6MutationsStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateNamespace(namespace:completionHandler:)")));
- (id<Store6MutationsKotlinx_coroutines_coreFlow>)streamKey:(id<Store6MutationsStore6_coreStoreKey>)key freshness:(id<Store6MutationsStore6_coreFreshness>)freshness __attribute__((swift_name("stream(key:freshness:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationStore")))
@interface Store6MutationsMutationStore<K, V> : Store6MutationsBase <Store6MutationsStore6_coreStore>

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearKey:(K)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clear(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearNamespaceNamespace:(Store6MutationsStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearNamespace(namespace:completionHandler:)")));
- (void)close __attribute__((swift_name("close()")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deadLettersWithCompletionHandler:(void (^)(NSArray<Store6MutationsDeadLetter *> * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("deadLetters(completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)drainWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("drain(completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)drainKey:(K)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("drain(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)getKey:(K)key freshness:(id<Store6MutationsStore6_coreFreshness>)freshness completionHandler:(void (^)(V _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("get(key:freshness:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateKey:(K)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidate(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateNamespaceNamespace:(Store6MutationsStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateNamespace(namespace:completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)mutateKey:(K)key ref:(Store6MutationsMutatorRef<K, V, id> *)ref args:(id)args completionHandler:(void (^)(NSString * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("mutate(key:ref:args:completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)pendingKey:(K)key completionHandler:(void (^)(NSArray<Store6MutationsPendingIntent *> * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("pending(key:completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)pendingWritesWithCompletionHandler:(void (^)(NSArray<Store6MutationsPendingIntent *> * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("pendingWrites(completionHandler:)")));
- (id<Store6MutationsKotlinx_coroutines_coreFlow>)streamKey:(K)key freshness:(id<Store6MutationsStore6_coreFreshness>)freshness __attribute__((swift_name("stream(key:freshness:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsKotlinx_coroutines_coreSharedFlow> events __attribute__((swift_name("events")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsKotlinx_coroutines_coreFlow> keyEvents __attribute__((swift_name("keyEvents")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) id<Store6MutationsKotlinx_coroutines_coreSharedFlow> poisoned __attribute__((swift_name("poisoned")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationStoreBuilder")))
@interface Store6MutationsMutationStoreBuilder<K, V> : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)bookkeeperBookkeeper:(id<Store6MutationsStore6_coreBookkeeper>)bookkeeper __attribute__((swift_name("bookkeeper(bookkeeper:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)conflictsConfigure:(void (^)(Store6MutationsMutationConflictBuilder<K, V> *))configure __attribute__((swift_name("conflicts(configure:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)fetcherFetch:(id<Store6MutationsKotlinSuspendFunction1>)fetch __attribute__((swift_name("fetcher(fetch:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)fetcherFetcher:(id<Store6MutationsStore6_coreFetcher>)fetcher __attribute__((swift_name("fetcher(fetcher:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)fetcherOfResultFetch:(id<Store6MutationsKotlinSuspendFunction1>)fetch __attribute__((swift_name("fetcherOfResult(fetch:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)freshnessValidatorValidator:(id<Store6MutationsStore6_coreFreshnessValidator>)validator __attribute__((swift_name("freshnessValidator(validator:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)maxIdleKeysCount:(int32_t)count __attribute__((swift_name("maxIdleKeys(count:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)persistenceSot:(id<Store6MutationsStore6_coreSourceOfTruth>)sot __attribute__((swift_name("persistence(sot:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)telemetryTelemetry:(id<Store6MutationsStore6_coreStoreTelemetry>)telemetry __attribute__((swift_name("telemetry(telemetry:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)wallClockWallClock:(id<Store6MutationsStore6_coreWallClock>)wallClock __attribute__((swift_name("wallClock(wallClock:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutatorRef")))
@interface Store6MutationsMutatorRef<K, V, A> : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *id __attribute__((swift_name("id")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutatorRegistry")))
@interface Store6MutationsMutatorRegistry<K, V> : Store6MutationsBase
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutatorRegistryBuilder")))
@interface Store6MutationsMutatorRegistryBuilder<K, V> : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (Store6MutationsMutatorRef<K, V, id> *)createId:(NSString *)id version:(int32_t)version codec:(id<Store6MutationsMutationCodec>)codec stales:(Store6MutationsStaleSet<K> *(^)(K, id))stales project:(V (^)(id))project __attribute__((swift_name("create(id:version:codec:stales:project:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (Store6MutationsMutatorRef<K, V, Store6MutationsKotlinUnit *> *)deleteId:(NSString *)id stales:(Store6MutationsStaleSet<K> *(^)(K, Store6MutationsKotlinUnit *))stales __attribute__((swift_name("delete(id:stales:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (Store6MutationsMutatorRef<K, V, id> *)mutatorId:(NSString *)id version:(int32_t)version codec:(id<Store6MutationsMutationCodec>)codec stales:(Store6MutationsStaleSet<K> *(^)(K, id))stales project:(id<Store6MutationsMutationPresence> _Nullable (^)(id<Store6MutationsMutationPresence>, id))project __attribute__((swift_name("mutator(id:version:codec:stales:project:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (Store6MutationsMutatorRef<K, V, id> *)updateId:(NSString *)id version:(int32_t)version codec:(id<Store6MutationsMutationCodec>)codec stales:(Store6MutationsStaleSet<K> *(^)(K, id))stales project:(V (^)(V, id))project __attribute__((swift_name("update(id:version:codec:stales:project:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (Store6MutationsMutatorRef<K, V, id> *)upsertId:(NSString *)id version:(int32_t)version codec:(id<Store6MutationsMutationCodec>)codec stales:(Store6MutationsStaleSet<K> *(^)(K, id))stales project:(id<Store6MutationsMutationPresence> (^)(id<Store6MutationsMutationPresence>, id))project __attribute__((swift_name("upsert(id:version:codec:stales:project:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PendingIntent")))
@interface Store6MutationsPendingIntent : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int32_t attempt __attribute__((swift_name("attempt")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *canonicalId __attribute__((swift_name("canonicalId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t createdAtEpochMillis __attribute__((swift_name("createdAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutatorId __attribute__((swift_name("mutatorId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly, getter=namespace) NSString *namespace_ __attribute__((swift_name("namespace_")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsMutationPendingState *state __attribute__((swift_name("state")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PoisonedIntent")))
@interface Store6MutationsPoisonedIntent : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) Store6MutationsKotlinThrowable *failure __attribute__((swift_name("failure")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutatorId __attribute__((swift_name("mutatorId")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StaleSet")))
@interface Store6MutationsStaleSet<K> : Store6MutationsBase
- (instancetype)initWithKeys:(NSSet<K> *)keys namespaces:(NSSet<Store6MutationsStore6_coreStoreNamespace *> *)namespaces __attribute__((swift_name("init(keys:namespaces:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSSet<K> *keys __attribute__((swift_name("keys")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSSet<Store6MutationsStore6_coreStoreNamespace *> *namespaces __attribute__((swift_name("namespaces")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationStoreKt")))
@interface Store6MutationsMutationStoreKt : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
+ (Store6MutationsMutationStore<id<Store6MutationsStore6_coreStoreKey>, id> *)mutationStoreRegistry:(Store6MutationsMutatorRegistry<id<Store6MutationsStore6_coreStoreKey>, id> *)registry server:(id<Store6MutationsMutationServer>)server keyResolver:(id<Store6MutationsMutationKeyResolver>)keyResolver valueCodecVersion:(int32_t)valueCodecVersion valueCodec:(id<Store6MutationsMutationCodec>)valueCodec configure:(void (^)(Store6MutationsMutationStoreBuilder<id<Store6MutationsStore6_coreStoreKey>, id> *))configure __attribute__((swift_name("mutationStore(registry:server:keyResolver:valueCodecVersion:valueCodec:configure:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutatorRegistryKt")))
@interface Store6MutationsMutatorRegistryKt : Store6MutationsBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
+ (Store6MutationsMutatorRegistry<id<Store6MutationsStore6_coreStoreKey>, id> *)mutatorRegistryConfigure:(void (^)(Store6MutationsMutatorRegistryBuilder<id<Store6MutationsStore6_coreStoreKey>, id> *))configure __attribute__((swift_name("mutatorRegistry(configure:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinByteArray")))
@interface Store6MutationsKotlinByteArray : Store6MutationsBase
+ (instancetype)arrayWithSize:(int32_t)size __attribute__((swift_name("init(size:)")));
+ (instancetype)arrayWithSize:(int32_t)size init:(Store6MutationsByte *(^)(Store6MutationsInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (int8_t)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (Store6MutationsKotlinByteIterator *)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(int8_t)value __attribute__((swift_name("set(index:value:)")));
@property (readonly) int32_t size __attribute__((swift_name("size")));
@end

__attribute__((swift_name("Store6_coreStoreMeta")))
@protocol Store6MutationsStore6_coreStoreMeta
@required
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@property (readonly) int64_t writtenAtEpochMillis __attribute__((swift_name("writtenAtEpochMillis")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinEnumCompanion")))
@interface Store6MutationsKotlinEnumCompanion : Store6MutationsBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6MutationsKotlinEnumCompanion *shared __attribute__((swift_name("shared")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinArray")))
@interface Store6MutationsKotlinArray<T> : Store6MutationsBase
+ (instancetype)arrayWithSize:(int32_t)size init:(T _Nullable (^)(Store6MutationsInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (T _Nullable)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (id<Store6MutationsKotlinIterator>)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(T _Nullable)value __attribute__((swift_name("set(index:value:)")));
@property (readonly) int32_t size __attribute__((swift_name("size")));
@end

__attribute__((swift_name("KotlinThrowable")))
@interface Store6MutationsKotlinThrowable : Store6MutationsBase
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6MutationsKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6MutationsKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   kotlin.experimental.ExperimentalNativeApi
*/
- (Store6MutationsKotlinArray<NSString *> *)getStackTrace __attribute__((swift_name("getStackTrace()")));
- (void)printStackTrace __attribute__((swift_name("printStackTrace()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) Store6MutationsKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString * _Nullable message __attribute__((swift_name("message")));
- (NSError *)asError __attribute__((swift_name("asError()")));
@end

__attribute__((swift_name("KotlinException")))
@interface Store6MutationsKotlinException : Store6MutationsKotlinThrowable
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6MutationsKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6MutationsKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("KotlinRuntimeException")))
@interface Store6MutationsKotlinRuntimeException : Store6MutationsKotlinException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6MutationsKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6MutationsKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("KotlinIllegalStateException")))
@interface Store6MutationsKotlinIllegalStateException : Store6MutationsKotlinRuntimeException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6MutationsKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6MutationsKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end


/**
 * @note annotations
 *   kotlin.SinceKotlin(version="1.4")
*/
__attribute__((swift_name("KotlinCancellationException")))
@interface Store6MutationsKotlinCancellationException : Store6MutationsKotlinIllegalStateException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6MutationsKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6MutationsKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("Store6_coreStoreKey")))
@protocol Store6MutationsStore6_coreStoreKey
@required
- (NSString *)canonicalId __attribute__((swift_name("canonicalId()")));
@property (readonly, getter=namespace) Store6MutationsStore6_coreStoreNamespace *namespace_ __attribute__((swift_name("namespace_")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreNamespace")))
@interface Store6MutationsStore6_coreStoreNamespace : Store6MutationsBase
- (instancetype)initWithValue:(NSString *)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
@property (readonly) NSString *value __attribute__((swift_name("value")));
@end

__attribute__((swift_name("Store6_coreFreshness")))
@protocol Store6MutationsStore6_coreFreshness
@required
@end

__attribute__((swift_name("Kotlinx_coroutines_coreFlow")))
@protocol Store6MutationsKotlinx_coroutines_coreFlow
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6MutationsKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreSharedFlow")))
@protocol Store6MutationsKotlinx_coroutines_coreSharedFlow <Store6MutationsKotlinx_coroutines_coreFlow>
@required
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreBookkeeper")))
@protocol Store6MutationsStore6_coreBookkeeper
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)advanceGlobalStaleWatermarkWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("advanceGlobalStaleWatermark(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)advanceStaleWatermarkNamespace:(Store6MutationsStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("advanceStaleWatermark(namespace:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetKey:(id<Store6MutationsStore6_coreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forget(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forgetAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetNamespaceNamespace:(Store6MutationsStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forgetNamespace(namespace:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)markStaleKey:(id<Store6MutationsStore6_coreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("markStale(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)recordFailureKey:(id<Store6MutationsStore6_coreStoreKey>)key atEpochMillis:(int64_t)atEpochMillis completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("recordFailure(key:atEpochMillis:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)recordSuccessKey:(id<Store6MutationsStore6_coreStoreKey>)key meta:(id<Store6MutationsStore6_coreStoreMeta>)meta completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("recordSuccess(key:meta:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)statusKey:(id<Store6MutationsStore6_coreStoreKey>)key completionHandler:(void (^)(Store6MutationsStore6_coreKeyStatus * _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("status(key:completionHandler:)")));
@end

__attribute__((swift_name("KotlinFunction")))
@protocol Store6MutationsKotlinFunction
@required
@end

__attribute__((swift_name("KotlinSuspendFunction1")))
@protocol Store6MutationsKotlinSuspendFunction1 <Store6MutationsKotlinFunction>
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invokeP1:(id _Nullable)p1 completionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("invoke(p1:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreFetcher")))
@protocol Store6MutationsStore6_coreFetcher
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)fetchKey:(id<Store6MutationsStore6_coreStoreKey>)key etag:(NSString * _Nullable)etag completionHandler:(void (^)(id<Store6MutationsStore6_coreFetcherResult> _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("fetch(key:etag:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreFreshnessValidator")))
@protocol Store6MutationsStore6_coreFreshnessValidator
@required
- (id<Store6MutationsStore6_coreFetchPlan>)planContext:(Store6MutationsStore6_coreFreshnessContext *)context __attribute__((swift_name("plan(context:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreSourceOfTruth")))
@protocol Store6MutationsStore6_coreSourceOfTruth
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteKey:(id<Store6MutationsStore6_coreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("delete(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("deleteAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteNamespaceNamespace:(Store6MutationsStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("deleteNamespace(namespace:completionHandler:)")));
- (id<Store6MutationsKotlinx_coroutines_coreFlow>)readerKey:(id<Store6MutationsStore6_coreStoreKey>)key __attribute__((swift_name("reader(key:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)writeKey:(id<Store6MutationsStore6_coreStoreKey>)key value:(id)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("write(key:value:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreStoreTelemetry")))
@protocol Store6MutationsStore6_coreStoreTelemetry
@required
- (void)onClearedKey:(id<Store6MutationsStore6_coreStoreKey>)key __attribute__((swift_name("onCleared(key:)")));
- (void)onFetchFailedKey:(id<Store6MutationsStore6_coreStoreKey>)key error:(Store6MutationsStore6_coreStoreError *)error duration:(int64_t)duration __attribute__((swift_name("onFetchFailed(key:error:duration:)")));
- (void)onFetchStartedKey:(id<Store6MutationsStore6_coreStoreKey>)key __attribute__((swift_name("onFetchStarted(key:)")));
- (void)onFetchSucceededKey:(id<Store6MutationsStore6_coreStoreKey>)key duration:(int64_t)duration __attribute__((swift_name("onFetchSucceeded(key:duration:)")));
- (void)onInvalidatedKey:(id<Store6MutationsStore6_coreStoreKey>)key __attribute__((swift_name("onInvalidated(key:)")));
- (void)onServeKey:(id<Store6MutationsStore6_coreStoreKey>)key origin:(Store6MutationsStore6_coreOrigin *)origin __attribute__((swift_name("onServe(key:origin:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreWallClock")))
@protocol Store6MutationsStore6_coreWallClock
@required
- (int64_t)nowEpochMillis __attribute__((swift_name("nowEpochMillis()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinUnit")))
@interface Store6MutationsKotlinUnit : Store6MutationsBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)unit __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6MutationsKotlinUnit *shared __attribute__((swift_name("shared")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((swift_name("KotlinIterator")))
@protocol Store6MutationsKotlinIterator
@required
- (BOOL)hasNext __attribute__((swift_name("hasNext()")));
- (id _Nullable)next __attribute__((swift_name("next()")));
@end

__attribute__((swift_name("KotlinByteIterator")))
@interface Store6MutationsKotlinByteIterator : Store6MutationsBase <Store6MutationsKotlinIterator>
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (Store6MutationsByte *)next __attribute__((swift_name("next()")));
- (int8_t)nextByte __attribute__((swift_name("nextByte()")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreFlowCollector")))
@protocol Store6MutationsKotlinx_coroutines_coreFlowCollector
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(id _Nullable)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreKeyStatus")))
@interface Store6MutationsStore6_coreKeyStatus : Store6MutationsBase
- (instancetype)initWithMeta:(id<Store6MutationsStore6_coreStoreMeta> _Nullable)meta lastSuccessSequence:(Store6MutationsLong * _Nullable)lastSuccessSequence lastFailureAtEpochMillis:(Store6MutationsLong * _Nullable)lastFailureAtEpochMillis consecutiveFailures:(int32_t)consecutiveFailures durablyStale:(BOOL)durablyStale __attribute__((swift_name("init(meta:lastSuccessSequence:lastFailureAtEpochMillis:consecutiveFailures:durablyStale:)"))) __attribute__((objc_designated_initializer));
@property (readonly) int32_t consecutiveFailures __attribute__((swift_name("consecutiveFailures")));
@property (readonly) BOOL durablyStale __attribute__((swift_name("durablyStale")));
@property (readonly) Store6MutationsLong * _Nullable lastFailureAtEpochMillis __attribute__((swift_name("lastFailureAtEpochMillis")));
@property (readonly) Store6MutationsLong * _Nullable lastSuccessSequence __attribute__((swift_name("lastSuccessSequence")));
@property (readonly) id<Store6MutationsStore6_coreStoreMeta> _Nullable meta __attribute__((swift_name("meta")));
@end

__attribute__((swift_name("Store6_coreFetcherResult")))
@protocol Store6MutationsStore6_coreFetcherResult
@required
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("Store6_coreFetchPlan")))
@protocol Store6MutationsStore6_coreFetchPlan
@required
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFreshnessContext")))
@interface Store6MutationsStore6_coreFreshnessContext : Store6MutationsBase
- (instancetype)initWithHasResidentValue:(BOOL)hasResidentValue meta:(id<Store6MutationsStore6_coreStoreMeta> _Nullable)meta epochStale:(BOOL)epochStale freshness:(id<Store6MutationsStore6_coreFreshness>)freshness nowEpochMillis:(int64_t)nowEpochMillis status:(Store6MutationsStore6_coreKeyStatus * _Nullable)status __attribute__((swift_name("init(hasResidentValue:meta:epochStale:freshness:nowEpochMillis:status:)"))) __attribute__((objc_designated_initializer));
@property (readonly) BOOL epochStale __attribute__((swift_name("epochStale")));
@property (readonly) id<Store6MutationsStore6_coreFreshness> freshness __attribute__((swift_name("freshness")));
@property (readonly) BOOL hasResidentValue __attribute__((swift_name("hasResidentValue")));
@property (readonly) id<Store6MutationsStore6_coreStoreMeta> _Nullable meta __attribute__((swift_name("meta")));
@property (readonly) int64_t nowEpochMillis __attribute__((swift_name("nowEpochMillis")));
@property (readonly) Store6MutationsStore6_coreKeyStatus * _Nullable status __attribute__((swift_name("status")));
@end

__attribute__((swift_name("Store6_coreStoreError")))
@interface Store6MutationsStore6_coreStoreError : Store6MutationsBase
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreOrigin")))
@interface Store6MutationsStore6_coreOrigin : Store6MutationsKotlinEnum<Store6MutationsStore6_coreOrigin *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) Store6MutationsStore6_coreOrigin *memory __attribute__((swift_name("memory")));
@property (class, readonly) Store6MutationsStore6_coreOrigin *sot __attribute__((swift_name("sot")));
@property (class, readonly) Store6MutationsStore6_coreOrigin *fetcher __attribute__((swift_name("fetcher")));
@property (class, readonly) Store6MutationsStore6_coreOrigin *overlay __attribute__((swift_name("overlay")));
+ (Store6MutationsKotlinArray<Store6MutationsStore6_coreOrigin *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<Store6MutationsStore6_coreOrigin *> *entries __attribute__((swift_name("entries")));
@end

#pragma pop_macro("_Nullable_result")
#pragma clang diagnostic pop
NS_ASSUME_NONNULL_END
