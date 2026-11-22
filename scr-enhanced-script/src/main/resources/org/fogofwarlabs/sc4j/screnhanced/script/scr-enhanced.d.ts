// SC:R Enhanced Script, script API version 1: the declarations of everything a map script sees except the lower layer core (core.d.ts).
// The declarations stand alone: use them with "noLib": true, as the jsconfig.json of a new script project does, so that no browser or
// Node.js global is offered.
//
// Scripts run in Rhino 1.8.1's interpreter: no class, async/await, spread (...), import/export, or for (const ...) in any form (of, in or a
// counting loop): use let. A const declared in a loop body keeps its first value, and the functions created in the body of a for (let ...)
// loop share one binding of its variables; a let declared in the loop body is new in every iteration. Promise jobs run when the call into the
// script that queued them returns ({@link Promise}). There is no eval, no Function from strings, and no Date, timer or Java access.
// TypeScript compiled by tsc with target ES5 and module commonjs runs with those forms, its helpers included (guide section 2.6).
//
// A hook is a method of the object given to defineHooks, or a top-level function, or variable holding one, with a hook name; the latter is a
// hook of its module exported or not: give helpers other names.
//
// Shared code is the module top level, onStart, onFrame, the event and decision hooks, the timers of game.every and game.after, and the
// channel handlers. It runs identically on every player's game and may change the game state. Local code is onLocalFrame, onRender and the
// local input hooks (onLocal...). It runs only on this player's machine, for display and input, and must not change anything shared code
// reads. A member documented "Shared code only." throws a TypeError in local code, "Local code only." throws a TypeError in shared code, and
// "Only in onRender." throws a TypeError outside onRender; a member without such a note works in both. A member that changes the game state
// also throws a TypeError while the game is read-only, inside a decision hook ({@link Game.isReadOnly}).
//
// Positions and distances are map pixels unless a member names tiles (32 pixels); type and player ids are 0-based. An object that stays with a
// unit, sprite, image or bullet throws an Error ("... does not exist") once that thing is gone; values outside their range throw a RangeError
// and arguments of the wrong kind a TypeError.

// ---------------------------------------------------------------------------------------------------------------------------------------
// The ECMAScript standard objects the runtime provides, as far as Rhino implements them.
// ---------------------------------------------------------------------------------------------------------------------------------------

interface Object {
    constructor: Function;

    toString(): string;

    toLocaleString(): string;

    valueOf(): Object;

    hasOwnProperty(key: PropertyKey): boolean;

    isPrototypeOf(value: Object): boolean;

    propertyIsEnumerable(key: PropertyKey): boolean;
}

interface PropertyDescriptor {
    configurable?: boolean;
    enumerable?: boolean;
    value?: any;
    writable?: boolean;

    get?(): any;

    set?(value: any): void;
}

interface PropertyDescriptorMap {
    [key: string]: PropertyDescriptor;
}

interface ObjectConstructor {
    new(value?: any): Object;

    (value?: any): any;

    readonly prototype: Object;

    getPrototypeOf(value: any): any;

    setPrototypeOf(value: any, prototype: object | null): any;

    getOwnPropertyNames(value: any): string[];

    getOwnPropertyDescriptor(value: any, key: PropertyKey): PropertyDescriptor | undefined;

    defineProperty<T>(value: T, key: PropertyKey, attributes: PropertyDescriptor): T;

    defineProperties<T>(value: T, properties: PropertyDescriptorMap): T;

    create(prototype: object | null, properties?: PropertyDescriptorMap): any;

    keys(value: object): string[];

    values<T>(value: { [key: string]: T }): T[];

    entries<T>(value: { [key: string]: T }): [string, T][];

    fromEntries<T>(entries: Iterable<readonly [PropertyKey, T]>): { [key: string]: T };

    assign<T, U>(target: T, source: U): T & U;

    assign(target: object, ...sources: any[]): any;

    freeze<T>(value: T): Readonly<T>;

    isFrozen(value: any): boolean;

    seal<T>(value: T): T;

    isSealed(value: any): boolean;

    preventExtensions<T>(value: T): T;

    isExtensible(value: any): boolean;

    is(first: any, second: any): boolean;

    hasOwn(value: object, key: PropertyKey): boolean;
}

declare var Object: ObjectConstructor;

interface Function {
    apply(this: Function, thisArgument: any, argumentArray?: any): any;

    call(this: Function, thisArgument: any, ...argumentList: any[]): any;

    bind(this: Function, thisArgument: any, ...argumentList: any[]): any;

    toString(): string;

    readonly length: number;
    readonly name: string;
}

interface FunctionConstructor {
    readonly prototype: Function;
}

/**
 * The function constructor throws a {@link TypeError}: scripts cannot create functions from strings.
 */
declare var Function: FunctionConstructor;

interface CallableFunction extends Function {
}

interface NewableFunction extends Function {
}

interface IArguments {
    [index: number]: any;

    length: number;
}

interface String {
    readonly length: number;

    readonly [index: number]: string;

    toString(): string;

    valueOf(): string;

    charAt(position: number): string;

    charCodeAt(index: number): number;

    codePointAt(position: number): number | undefined;

    at(index: number): string | undefined;

    concat(...strings: string[]): string;

    includes(searchString: string, position?: number): boolean;

    startsWith(searchString: string, position?: number): boolean;

    endsWith(searchString: string, endPosition?: number): boolean;

    indexOf(searchString: string, position?: number): number;

    lastIndexOf(searchString: string, position?: number): number;

    localeCompare(that: string): number;

    match(pattern: string | RegExp): RegExpMatchArray | null;

    replace(pattern: string | RegExp, replacement: string | ((substring: string, ...rest: any[]) => string)): string;

    replaceAll(pattern: string | RegExp, replacement: string | ((substring: string, ...rest: any[]) => string)): string;

    search(pattern: string | RegExp): number;

    slice(start?: number, end?: number): string;

    split(separator: string | RegExp, limit?: number): string[];

    substring(start: number, end?: number): string;

    toLowerCase(): string;

    toUpperCase(): string;

    trim(): string;

    trimStart(): string;

    trimEnd(): string;

    padStart(maximumLength: number, fillString?: string): string;

    padEnd(maximumLength: number, fillString?: string): string;

    repeat(count: number): string;

    normalize(form?: string): string;

    [Symbol.iterator](): IterableIterator<string>;
}

interface StringConstructor {
    new(value?: any): String;

    (value?: any): string;

    readonly prototype: String;

    fromCharCode(...codes: number[]): string;

    fromCodePoint(...codePoints: number[]): string;
}

declare var String: StringConstructor;

interface Boolean {
    valueOf(): boolean;
}

interface BooleanConstructor {
    new(value?: any): Boolean;

    <T>(value?: T): boolean;

    readonly prototype: Boolean;
}

declare var Boolean: BooleanConstructor;

interface Number {
    toString(radix?: number): string;

    toFixed(fractionDigits?: number): string;

    toExponential(fractionDigits?: number): string;

    toPrecision(precision?: number): string;

    valueOf(): number;
}

interface NumberConstructor {
    new(value?: any): Number;

    (value?: any): number;

    readonly prototype: Number;
    readonly MAX_VALUE: number;
    readonly MIN_VALUE: number;
    readonly NaN: number;
    readonly NEGATIVE_INFINITY: number;
    readonly POSITIVE_INFINITY: number;
    readonly MAX_SAFE_INTEGER: number;
    readonly MIN_SAFE_INTEGER: number;
    readonly EPSILON: number;

    isFinite(value: unknown): boolean;

    isInteger(value: unknown): boolean;

    isNaN(value: unknown): boolean;

    isSafeInteger(value: unknown): boolean;

    parseFloat(text: string): number;

    parseInt(text: string, radix?: number): number;
}

declare var Number: NumberConstructor;

interface RegExpMatchArray extends Array<string> {
    index?: number;
    input?: string;
}

interface RegExpExecArray extends Array<string> {
    index: number;
    input: string;
}

interface RegExp {
    exec(text: string): RegExpExecArray | null;

    test(text: string): boolean;

    readonly source: string;
    readonly flags: string;
    readonly global: boolean;
    readonly ignoreCase: boolean;
    readonly multiline: boolean;
    lastIndex: number;
}

interface RegExpConstructor {
    new(pattern: RegExp | string, flags?: string): RegExp;

    (pattern: RegExp | string, flags?: string): RegExp;

    readonly prototype: RegExp;
}

declare var RegExp: RegExpConstructor;

interface ConcatArray<T> {
    readonly length: number;

    readonly [index: number]: T;

    join(separator?: string): string;

    slice(start?: number, end?: number): T[];
}

interface ReadonlyArray<T> {
    readonly length: number;

    readonly [index: number]: T;

    indexOf(searchElement: T, fromIndex?: number): number;

    includes(searchElement: T, fromIndex?: number): boolean;

    slice(start?: number, end?: number): T[];

    join(separator?: string): string;

    forEach(callback: (value: T, index: number, array: readonly T[]) => void, thisArgument?: any): void;

    map<U>(callback: (value: T, index: number, array: readonly T[]) => U, thisArgument?: any): U[];

    filter<S extends T>(predicate: (value: T, index: number, array: readonly T[]) => value is S, thisArgument?: any): S[];

    filter(predicate: (value: T, index: number, array: readonly T[]) => unknown, thisArgument?: any): T[];

    [Symbol.iterator](): IterableIterator<T>;
}

interface Array<T> {
    length: number;

    [index: number]: T;

    toString(): string;

    at(index: number): T | undefined;

    concat(...items: (T | ConcatArray<T>)[]): T[];

    copyWithin(target: number, start: number, end?: number): this;

    entries(): IterableIterator<[number, T]>;

    every(predicate: (value: T, index: number, array: T[]) => unknown, thisArgument?: any): boolean;

    fill(value: T, start?: number, end?: number): this;

    filter<S extends T>(predicate: (value: T, index: number, array: T[]) => value is S, thisArgument?: any): S[];

    filter(predicate: (value: T, index: number, array: T[]) => unknown, thisArgument?: any): T[];

    find(predicate: (value: T, index: number, array: T[]) => unknown, thisArgument?: any): T | undefined;

    findIndex(predicate: (value: T, index: number, array: T[]) => unknown, thisArgument?: any): number;

    findLast(predicate: (value: T, index: number, array: T[]) => unknown, thisArgument?: any): T | undefined;

    findLastIndex(predicate: (value: T, index: number, array: T[]) => unknown, thisArgument?: any): number;

    flat(depth?: number): any[];

    flatMap<U>(callback: (value: T, index: number, array: T[]) => U | readonly U[], thisArgument?: any): U[];

    forEach(callback: (value: T, index: number, array: T[]) => void, thisArgument?: any): void;

    includes(searchElement: T, fromIndex?: number): boolean;

    indexOf(searchElement: T, fromIndex?: number): number;

    join(separator?: string): string;

    keys(): IterableIterator<number>;

    lastIndexOf(searchElement: T, fromIndex?: number): number;

    map<U>(callback: (value: T, index: number, array: T[]) => U, thisArgument?: any): U[];

    pop(): T | undefined;

    push(...items: T[]): number;

    reduce(callback: (previous: T, current: T, index: number, array: T[]) => T): T;

    reduce<U>(callback: (previous: U, current: T, index: number, array: T[]) => U, initialValue: U): U;

    reduceRight(callback: (previous: T, current: T, index: number, array: T[]) => T): T;

    reduceRight<U>(callback: (previous: U, current: T, index: number, array: T[]) => U, initialValue: U): U;

    reverse(): T[];

    shift(): T | undefined;

    slice(start?: number, end?: number): T[];

    some(predicate: (value: T, index: number, array: T[]) => unknown, thisArgument?: any): boolean;

    sort(compare?: (first: T, second: T) => number): this;

    splice(start: number, deleteCount?: number, ...items: T[]): T[];

    toReversed(): T[];

    toSorted(compare?: (first: T, second: T) => number): T[];

    toSpliced(start: number, deleteCount?: number, ...items: T[]): T[];

    unshift(...items: T[]): number;

    values(): IterableIterator<T>;

    with(index: number, value: T): T[];

    [Symbol.iterator](): IterableIterator<T>;
}

interface ArrayConstructor {
    new<T>(...items: T[]): T[];

    <T>(...items: T[]): T[];

    readonly prototype: any[];

    isArray(value: any): value is any[];

    from<T>(items: Iterable<T> | ArrayLike<T>): T[];

    from<T, U>(items: Iterable<T> | ArrayLike<T>, mapping: (value: T, index: number) => U): U[];

    of<T>(...items: T[]): T[];
}

declare var Array: ArrayConstructor;

interface ArrayLike<T> {
    readonly length: number;

    readonly [index: number]: T;
}

type PropertyKey = string | number | symbol;

type Partial<T> = { [P in keyof T]?: T[P] };

type Readonly<T> = { readonly [P in keyof T]: T[P] };

type Record<K extends keyof any, T> = { [P in K]: T };

interface TemplateStringsArray extends ReadonlyArray<string> {
    readonly raw: readonly string[];
}

interface Symbol {
    toString(): string;

    readonly description: string | undefined;
}

interface SymbolConstructor {
    (description?: string | number): symbol;

    readonly prototype: Symbol;
    readonly iterator: unique symbol;
}

declare var Symbol: SymbolConstructor;

interface IteratorYieldResult<T> {
    done?: false;
    value: T;
}

interface IteratorReturnResult<TReturn> {
    done: true;
    value: TReturn;
}

type IteratorResult<T, TReturn = any> = IteratorYieldResult<T> | IteratorReturnResult<TReturn>;

interface Iterator<T, TReturn = any, TNext = any> {
    next(...[value]: [] | [TNext]): IteratorResult<T, TReturn>;

    return?(value?: TReturn): IteratorResult<T, TReturn>;

    throw?(error?: any): IteratorResult<T, TReturn>;
}

interface Iterable<T, TReturn = any, TNext = any> {
    [Symbol.iterator](): Iterator<T, TReturn, TNext>;
}

interface IterableIterator<T, TReturn = any, TNext = any> extends Iterator<T, TReturn, TNext> {
    [Symbol.iterator](): IterableIterator<T, TReturn, TNext>;
}

/**
 * The object a generator function returns.
 */
interface Generator<T = unknown, TReturn = any, TNext = any> extends IterableIterator<T, TReturn, TNext> {
    next(...[value]: [] | [TNext]): IteratorResult<T, TReturn>;

    return(value: TReturn): IteratorResult<T, TReturn>;

    throw(error: any): IteratorResult<T, TReturn>;

    [Symbol.iterator](): Generator<T, TReturn, TNext>;
}

interface Map<K, V> {
    readonly size: number;

    clear(): void;

    delete(key: K): boolean;

    entries(): IterableIterator<[K, V]>;

    forEach(callback: (value: V, key: K, map: Map<K, V>) => void, thisArgument?: any): void;

    get(key: K): V | undefined;

    has(key: K): boolean;

    keys(): IterableIterator<K>;

    set(key: K, value: V): this;

    values(): IterableIterator<V>;

    [Symbol.iterator](): IterableIterator<[K, V]>;
}

interface MapConstructor {
    new<K, V>(entries?: Iterable<readonly [K, V]> | null): Map<K, V>;

    readonly prototype: Map<any, any>;
}

declare var Map: MapConstructor;

interface Set<T> {
    readonly size: number;

    add(value: T): this;

    clear(): void;

    delete(value: T): boolean;

    entries(): IterableIterator<[T, T]>;

    forEach(callback: (value: T, key: T, set: Set<T>) => void, thisArgument?: any): void;

    has(value: T): boolean;

    keys(): IterableIterator<T>;

    values(): IterableIterator<T>;

    [Symbol.iterator](): IterableIterator<T>;
}

interface SetConstructor {
    new<T>(values?: Iterable<T> | null): Set<T>;

    readonly prototype: Set<any>;
}

declare var Set: SetConstructor;

interface WeakMap<K extends object, V> {
    delete(key: K): boolean;

    get(key: K): V | undefined;

    has(key: K): boolean;

    set(key: K, value: V): this;
}

interface WeakMapConstructor {
    new<K extends object, V>(entries?: Iterable<readonly [K, V]> | null): WeakMap<K, V>;

    readonly prototype: WeakMap<object, any>;
}

declare var WeakMap: WeakMapConstructor;

interface WeakSet<T extends object> {
    add(value: T): this;

    delete(value: T): boolean;

    has(value: T): boolean;
}

interface WeakSetConstructor {
    new<T extends object>(values?: Iterable<T> | null): WeakSet<T>;

    readonly prototype: WeakSet<object>;
}

declare var WeakSet: WeakSetConstructor;

/**
 * A promise. Its jobs, the callbacks of then, catch and finally, run when the call into the script that queued them returns: the module
 * loading, the hook, the timer or the channel handler, before the next one and within the same instruction budget. A promise that is still
 * pending then settles only when a later call resolves or rejects it; promises do not wait for time, timers do ({@link Game.after}). A
 * callback that throws rejects the promise that then, catch or finally returned; a rejection that no callback handles is not logged.
 */
interface Promise<T> {
    then<R1 = T, R2 = never>(onFulfilled?: (value: T) => R1 | PromiseLike<R1>, onRejected?: (reason: any) => R2 | PromiseLike<R2>): Promise<R1 | R2>;

    catch<R = never>(onRejected?: (reason: any) => R | PromiseLike<R>): Promise<T | R>;

    finally(onFinally?: () => void): Promise<T>;
}

interface PromiseFulfilledResult<T> {
    status: 'fulfilled';
    value: T;
}

/**
 * The result of a rejected promise in {@link PromiseConstructor.allSettled}: Rhino 1.8.1 writes its status with a leading space.
 */
interface PromiseRejectedResult {
    status: ' rejected';
    reason: any;
}

type PromiseSettledResult<T> = PromiseFulfilledResult<T> | PromiseRejectedResult;

interface PromiseConstructor {
    /**
     * Creates a promise and calls the executor at once.
     */
    new<T>(executor: (resolve: (value: T | PromiseLike<T>) => void, reject: (reason?: any) => void) => void): Promise<T>;

    readonly prototype: Promise<any>;

    resolve(): Promise<void>;

    resolve<T>(value: T | PromiseLike<T>): Promise<T>;

    reject<T = never>(reason?: any): Promise<T>;

    all<T>(values: Iterable<T | Promise<T>>): Promise<T[]>;

    allSettled<T>(values: Iterable<T | Promise<T>>): Promise<PromiseSettledResult<T>[]>;

    /**
     * @returns a promise of the first fulfilled value, rejected with an AggregateError when every promise rejects
     */
    any<T>(values: Iterable<T | Promise<T>>): Promise<T>;

    race<T>(values: Iterable<T | Promise<T>>): Promise<T>;
}

declare var Promise: PromiseConstructor;

/**
 * An object with a then method, as await and a promise's resolve function take it.
 */
interface PromiseLike<T> {
    then<R1 = T, R2 = never>(onFulfilled?: ((value: T) => R1 | PromiseLike<R1>) | null,
                             onRejected?: ((reason: any) => R2 | PromiseLike<R2>) | null): PromiseLike<R1 | R2>;
}

/**
 * The constructor that the code TypeScript writes for async functions below ES2017 calls.
 */
type PromiseConstructorLike = new<T>(executor: (resolve: (value: T | PromiseLike<T>) => void, reject: (reason?: any) => void) => void) =>
    PromiseLike<T>;

interface JSON {
    parse(text: string, reviver?: (key: string, value: any) => any): any;

    stringify(value: any, replacer?: ((key: string, value: any) => any) | (number | string)[] | null, space?: string | number): string;
}

declare var JSON: JSON;

interface Error {
    name: string;
    message: string;
}

interface ErrorConstructor {
    new(message?: string): Error;

    (message?: string): Error;

    readonly prototype: Error;
}

declare var Error: ErrorConstructor;
declare var EvalError: ErrorConstructor;
declare var RangeError: ErrorConstructor;
declare var ReferenceError: ErrorConstructor;
declare var SyntaxError: ErrorConstructor;
declare var TypeError: ErrorConstructor;
declare var URIError: ErrorConstructor;

interface AggregateError extends Error {
    errors: any[];
}

interface AggregateErrorConstructor {
    new(errors: Iterable<any>, message?: string): AggregateError;

    (errors: Iterable<any>, message?: string): AggregateError;

    readonly prototype: Error;
}

declare var AggregateError: AggregateErrorConstructor;

declare var NaN: number;
declare var Infinity: number;

declare function parseInt(text: string, radix?: number): number;

declare function parseFloat(text: string): number;

declare function isNaN(value: number): boolean;

declare function isFinite(value: number): boolean;

declare function encodeURI(uri: string): string;

declare function encodeURIComponent(component: string | number | boolean): string;

declare function decodeURI(encodedUri: string): string;

declare function decodeURIComponent(encodedComponent: string): string;

/**
 * Deterministic math: every function gives the same result on every machine, and {@link Math.random} draws from the script RNG
 * ({@link random}).
 */
interface Math {
    readonly E: number;
    readonly LN10: number;
    readonly LN2: number;
    readonly LOG10E: number;
    readonly LOG2E: number;
    readonly PI: number;
    readonly SQRT1_2: number;
    readonly SQRT2: number;

    abs(value: number): number;

    acos(value: number): number;

    acosh(value: number): number;

    asin(value: number): number;

    asinh(value: number): number;

    atan(value: number): number;

    atan2(y: number, x: number): number;

    atanh(value: number): number;

    cbrt(value: number): number;

    ceil(value: number): number;

    clz32(value: number): number;

    cos(value: number): number;

    cosh(value: number): number;

    exp(value: number): number;

    expm1(value: number): number;

    floor(value: number): number;

    fround(value: number): number;

    hypot(...values: number[]): number;

    imul(first: number, second: number): number;

    log(value: number): number;

    log10(value: number): number;

    log1p(value: number): number;

    log2(value: number): number;

    max(...values: number[]): number;

    min(...values: number[]): number;

    pow(base: number, exponent: number): number;

    /**
     * @returns a number from the script RNG, at least 0 and below 1
     */
    random(): number;

    round(value: number): number;

    sign(value: number): number;

    sin(value: number): number;

    sinh(value: number): number;

    sqrt(value: number): number;

    tan(value: number): number;

    tanh(value: number): number;

    trunc(value: number): number;

    toSource(): string;
}

declare var Math: Math;

// ---------------------------------------------------------------------------------------------------------------------------------------
// The script runtime: modules, log and the script random numbers.
// ---------------------------------------------------------------------------------------------------------------------------------------

/**
 * The module object of a script. Each script of the package is a module: it runs once, in its own top-level scope, and shares its values
 * through {@link Module.exports}.
 */
interface Module {
    /**
     * The script name: the path of the script in the package, with {@code .js}, such as {@code lib/util.js}.
     */
    readonly id: string;

    /**
     * The value {@link require} returns for this script: at first the object that {@link exports} names. Assign another value to export it
     * instead.
     */
    exports: any;
}

/**
 * The module object of the current script.
 */
declare var module: Module;

/**
 * The first value of {@link Module.exports}: properties added to it are exported. Assigning another value to {@code exports} exports nothing;
 * assign {@link Module.exports} instead.
 */
declare var exports: any;

/**
 * Loads a script of the package as a module and returns its {@link Module.exports}. An id that starts with {@code ./} or {@code ../} names a
 * script relative to the folder of the requiring script, any other id a script relative to the package root; {@code .js} is added when the id
 * does not end with it. The script runs at its first require; later calls return its current {@link Module.exports}, also while it is still
 * loading, so that cycles end. An error of the loading script passes to the caller, and the next require loads the script again. Local code
 * may require a script that has loaded, but loads no new one.
 *
 * @param id the script id, such as {@code './bonus'} or {@code 'lib/util.js'}
 * @returns the {@link Module.exports} of the script
 * @throws TypeError if the id is not a string, or in local code if the script has not loaded
 * @throws Error if the id is empty, starts with {@code /}, has an empty segment or leaves the package root, or if the package has no such script
 */
declare function require(id: string): any;

/**
 * Writes lines to the SC:R Enhanced log, prefixed with the module name: the values are converted to strings and joined with spaces.
 */
interface Log {
    /**
     * Writes the values at the info level.
     */
    info(...values: any[]): void;

    /**
     * Writes the values at the warning level.
     */
    warn(...values: any[]): void;

    /**
     * Writes the values at the error level.
     */
    error(...values: any[]): void;
}

/**
 * The log of the current module.
 */
declare const log: Log;

/**
 * The random numbers of the script: seeded from the game seed, the same on every player's game and separate from the game's own random
 * numbers. Shared code only.
 */
interface Random {
    /**
     * @returns a number from 0 inclusive to 1 exclusive
     */
    next(): number;

    /**
     * @param bound an integer from 1 to 2^53
     * @returns an integer from 0 inclusive to the bound exclusive
     * @throws RangeError if the bound is not an integer from 1 to 2^53
     */
    nextInt(bound: number): number;

    /**
     * @param probability the probability from 0 to 1
     * @returns true with the probability
     */
    chance(probability: number): boolean;
}

/**
 * The random numbers of the script.
 */
declare const random: Random;

// ---------------------------------------------------------------------------------------------------------------------------------------
// The hooks: methods of the object given to defineHooks, or top-level functions of a script with these names. Every script (module) that has
// a hook gets the call, in load order.
// ---------------------------------------------------------------------------------------------------------------------------------------

/**
 * The hooks a script may define, as methods of the object given to {@link defineHooks}: {@code defineHooks({ onUnitDied(event) { ... } })},
 * or as top-level functions: {@code function onUnitDied(event) { ... }}. The runtime collects them per module once the module has loaded and
 * calls them from the game. A frame calls onStart (the first frame only), the due timers, the channel handlers of the frame's commands, the
 * event hooks ({@code on*}) of the frame's events ({@link CoreEvents.getFrameEvents}) and onFrame, in this order, at the frame start before the
 * triggers. The decision hooks ({@code onBefore*}) run inside the game routine and may change only the decision. A method of defineHooks gets
 * the type of its parameter from here; give a top-level function's parameter its type with JSDoc: {@code /** @param {UnitDiedEvent} event *\/}.
 *
 * The local hooks ({@code onLocal*}, onRender) are local code and run on this player's machine only, at once. The input hooks do not run in
 * replays or for observers; onLocalFrame and onRender do. Local code may read the game and the local information ({@link local}), move the
 * local screen and send ({@link Channel.send}, {@link PlayerValue.set}, {@link CoreCommand.submit}; nothing is sent in a replay or by an
 * observer), but changing the game, adding or cancelling a timer, creating a channel, drawing a random number or loading a new module throws a
 * TypeError.
 */
interface Hooks {
    /**
     * Called once at the first frame, before the timers and the other hooks of the frame.
     */
    onStart?(): void;

    /**
     * Called every game frame, before the triggers, after the timers, the channel handlers and the event hooks of the frame.
     *
     * @param frame the number of the game frame
     */
    onFrame?(frame: number): void;

    /**
     * Called for a unit that was created.
     */
    onUnitCreated?(event: UnitCreatedEvent): void;

    /**
     * Called for a unit whose construction or training finished.
     */
    onUnitCompleted?(event: UnitCompletedEvent): void;

    /**
     * Called for a unit that started to die, with its killer and the weapon when known.
     */
    onUnitDied?(event: UnitDiedEvent): void;

    /**
     * Called for hit point damage to a unit.
     */
    onUnitDamaged?(event: UnitDamagedEvent): void;

    /**
     * Called for an order issued to a unit.
     */
    onOrderIssued?(event: OrderIssuedEvent): void;

    /**
     * Called for a target that an automatic target search found.
     */
    onTargetAcquired?(event: TargetAcquiredEvent): void;

    /**
     * Called for a unit that a producer trained, with the producer.
     */
    onUnitTrained?(event: UnitTrainedEvent): void;

    /**
     * Called for a technology whose research finished.
     */
    onResearchCompleted?(event: ResearchCompletedEvent): void;

    /**
     * Called for an upgrade level whose research finished.
     */
    onUpgradeCompleted?(event: UpgradeCompletedEvent): void;

    /**
     * Called for a spell a unit cast.
     */
    onSpellCast?(event: SpellCastEvent): void;

    /**
     * Called for a bullet the game created.
     */
    onBulletCreated?(event: BulletCreatedEvent): void;

    /**
     * Called for a bullet that hit or missed its target.
     */
    onBulletHit?(event: BulletHitEvent): void;

    /**
     * Called for a unit that changed its owner.
     */
    onOwnerChanged?(event: OwnerChangedEvent): void;

    /**
     * Called for a unit that entered a transport or a Bunker.
     */
    onUnitLoaded?(event: UnitLoadedEvent): void;

    /**
     * Called for a unit that left a transport or a Bunker.
     */
    onUnitUnloaded?(event: UnitUnloadedEvent): void;

    /**
     * Called for a player who left the game or was dropped.
     */
    onPlayerLeft?(event: PlayerLeftEvent): void;

    /**
     * Called for a player who received a result: the triggers ended the player's game, or the player left.
     */
    onPlayerResult?(event: PlayerResultEvent): void;

    /**
     * Local code: called for a chat line the local player submitted, before it is sent.
     */
    onLocalChat?(event: LocalChatEvent): void;

    /**
     * Local code: called for a key the local player pressed while the game takes play keys, before the game's own keys.
     */
    onLocalKeyDown?(event: LocalKeyEvent): void;

    /**
     * Local code: called for a key the local player released.
     */
    onLocalKeyUp?(event: LocalKeyEvent): void;

    /**
     * Local code: called for a mouse button the local player pressed over the map, before the game's own handling.
     */
    onLocalMouseDown?(event: LocalMouseButtonEvent): void;

    /**
     * Local code: called for a mouse button the local player released.
     */
    onLocalMouseUp?(event: LocalMouseButtonEvent): void;

    /**
     * Local code: called for an enabled command card button the local player pressed, by a click or a hotkey, before its action runs;
     * consuming the press runs no action. Called in a live game only.
     */
    onLocalButtonPressed?(event: LocalButtonPressedEvent): void;

    /**
     * Local code: called for the unit or map point the local player targeted with a ScriptTarget button; consuming the target keeps the
     * targeting mode, as a refused target of an order does.
     */
    onLocalButtonTargeted?(event: LocalButtonTargetedEvent): void;

    /**
     * Local code: called after each game frame this machine plays, live or in a replay.
     *
     * @param frame the frame number that onFrame received
     */
    onLocalFrame?(frame: number): void;

    /**
     * Local code: called after onLocalFrame to draw; what it draws stays on screen until the next call. It has its own instruction budget: a
     * call over the budget draws nothing; a call that throws keeps what it drew.
     *
     * @param screen the screen to draw on
     */
    onRender?(screen: Screen): void;

    /**
     * Decides the damage of a weapon hit; the game is read-only meanwhile.
     */
    onBeforeDamage?(decision: DamageDecision): void;

    /**
     * Decides an order issued to a unit; the game is read-only meanwhile.
     */
    onBeforeOrder?(decision: OrderDecision): void;

    /**
     * Decides the target an automatic target search found; the game is read-only meanwhile.
     */
    onBeforeTargetAcquired?(decision: TargetAcquisitionDecision): void;
}

/**
 * Defines hooks of this module: each method of the object, named as a member of {@link Hooks}, becomes the module's hook, as a top-level
 * function of that name would: the same calls, in the same order, with the same budget and errors. A method runs with {@code this} set to the
 * object. The object's own enumerable properties are read once, at the call.
 *
 * Call it while the module loads, at its top level; a module may call it more than once with other hooks. A hook defined twice, by two calls
 * or by a call and a top-level function, throws an Error; a top-level function assigned after the call makes the loading fail when the module
 * has run to its end.
 *
 * @param hooks the hooks
 * @throws TypeError if hooks is no object, a property name is no hook name or a property is no function
 * @throws Error if the module has loaded or a hook is defined already
 */
declare function defineHooks(hooks: Hooks): void;

// ---------------------------------------------------------------------------------------------------------------------------------------
// The game API: the upper layer.
// ---------------------------------------------------------------------------------------------------------------------------------------

/**
 * A type of the game data or a constant: one object per id, so two of them compare with ===.
 */
interface GameType {
    /**
     * @returns the 0-based id, which the lower layer {@link core} takes
     */
    getId(): number;

    /**
     * @returns the game data name ("Terran Marine") or the constant's name
     */
    getName(): string;
}

/**
 * A table of constants by id; the named constants are also its properties.
 */
interface ConstantTable<T> {
    /**
     * @param id the 0-based id
     * @returns the constant of the id, or null for an id outside the table
     */
    get(id: number): T | null;

    /**
     * @returns the number of ids, from 0 to the count minus one
     */
    getCount(): number;
}

// BEGIN GENERATED DAT: the DAT columns of the game data tables.

/**
 * The units.dat values of the unit types: raw values of the current game.
 * <p>
 * The set functions change the game and work in shared code only. They throw a RangeError where the lower layer's
 * set functions do ({@link CoreDatUnits}).
 */
interface UnitTypeData {
    /** Graphics (below 209) */
    getFlingyType(): FlingyType;

    setFlingyType(flingyType: FlingyType): void;

    /** Subunit1 */
    getFirstSubunit(): UnitType | null;

    setFirstSubunit(unitType: UnitType | null): void;

    /** Subunit2 */
    getSecondSubunit(): UnitType | null;

    setSecondSubunit(unitType: UnitType | null): void;

    /** Infestation (unit types 106 to 201) */
    getInfestedType(): UnitType | null;

    setInfestedType(unitType: UnitType | null): void;

    /** Construction Animation */
    getConstructionImage(): ImageType;

    setConstructionImage(imageType: ImageType): void;

    /** Unit Direction */
    getInitialDirection(): number;

    setInitialDirection(value: number): void;

    /** Shield Enable */
    getShieldEnabled(): number;

    setShieldEnabled(value: number): void;

    /** Shield Amount */
    getShieldAmount(): number;

    setShieldAmount(value: number): void;

    /** Hit Points */
    getHitPoints(): number;

    setHitPoints(value: number): void;

    /** Elevation Level */
    getElevationLevel(): number;

    setElevationLevel(value: number): void;

    /** Unknown */
    getUnknownByte(): number;

    setUnknownByte(value: number): void;

    /** Sublabel */
    getSublabel(): number;

    setSublabel(value: number): void;

    /** Comp AI Idle */
    getComputerIdleOrder(): Order | null;

    setComputerIdleOrder(order: Order | null): void;

    /** Human AI Idle */
    getHumanIdleOrder(): Order | null;

    setHumanIdleOrder(order: Order | null): void;

    /** Return to Idle */
    getReturnToIdleOrder(): Order | null;

    setReturnToIdleOrder(order: Order | null): void;

    /** Attack Unit */
    getAttackUnitOrder(): Order | null;

    setAttackUnitOrder(order: Order | null): void;

    /** Attack Move */
    getAttackMoveOrder(): Order | null;

    setAttackMoveOrder(order: Order | null): void;

    /** Ground Weapon */
    getGroundWeapon(): WeaponType | null;

    setGroundWeapon(weaponType: WeaponType | null): void;

    /** Max Ground Hits */
    getMaximumGroundHits(): number;

    setMaximumGroundHits(value: number): void;

    /** Air Weapon */
    getAirWeapon(): WeaponType | null;

    setAirWeapon(weaponType: WeaponType | null): void;

    /** Max Air Hits */
    getMaximumAirHits(): number;

    setMaximumAirHits(value: number): void;

    /** AI Internal 0x1 */
    isComputerInternalAttackGroupIgnored(): boolean;

    setComputerInternalAttackGroupIgnored(set: boolean): void;

    /** AI Internal 0x2 */
    isComputerInternalTownUnit(): boolean;

    setComputerInternalTownUnit(set: boolean): void;

    /** Special Ability Flags 0x1 */
    isBuilding(): boolean;

    setBuilding(set: boolean): void;

    /** Special Ability Flags 0x2 */
    isAddon(): boolean;

    setAddon(set: boolean): void;

    /** Special Ability Flags 0x4 */
    isFlyer(): boolean;

    setFlyer(set: boolean): void;

    /** Special Ability Flags 0x8 */
    isWorker(): boolean;

    setWorker(set: boolean): void;

    /** Special Ability Flags 0x10 */
    isSubunit(): boolean;

    setSubunit(set: boolean): void;

    /** Special Ability Flags 0x20 */
    isFlyingBuilding(): boolean;

    setFlyingBuilding(set: boolean): void;

    /** Special Ability Flags 0x40 */
    isHero(): boolean;

    setHero(set: boolean): void;

    /** Special Ability Flags 0x80 */
    isRegeneratesHitPoints(): boolean;

    setRegeneratesHitPoints(set: boolean): void;

    /** Special Ability Flags 0x200 */
    isCloakable(): boolean;

    setCloakable(set: boolean): void;

    /** Special Ability Flags 0x400 */
    isTwoUnitsInOneEgg(): boolean;

    setTwoUnitsInOneEgg(set: boolean): void;

    /** Special Ability Flags 0x800 */
    isPowerup(): boolean;

    setPowerup(set: boolean): void;

    /** Special Ability Flags 0x1000 */
    isResourceDepot(): boolean;

    setResourceDepot(set: boolean): void;

    /** Special Ability Flags 0x2000 */
    isResourceContainer(): boolean;

    setResourceContainer(set: boolean): void;

    /** Special Ability Flags 0x4000 */
    isRobotic(): boolean;

    setRobotic(set: boolean): void;

    /** Special Ability Flags 0x8000 */
    isDetector(): boolean;

    setDetector(set: boolean): void;

    /** Special Ability Flags 0x10000 */
    isOrganic(): boolean;

    setOrganic(set: boolean): void;

    /** Special Ability Flags 0x20000 */
    isRequiresCreep(): boolean;

    setRequiresCreep(set: boolean): void;

    /** Special Ability Flags 0x80000 */
    isRequiresPsionicMatrix(): boolean;

    setRequiresPsionicMatrix(set: boolean): void;

    /** Special Ability Flags 0x100000 */
    isBurrowable(): boolean;

    setBurrowable(set: boolean): void;

    /** Special Ability Flags 0x200000 */
    isSpellcaster(): boolean;

    setSpellcaster(set: boolean): void;

    /** Special Ability Flags 0x400000 */
    isPermanentCloak(): boolean;

    setPermanentCloak(set: boolean): void;

    /** Special Ability Flags 0x1000000 */
    isIgnoreSupplyCheck(): boolean;

    setIgnoreSupplyCheck(set: boolean): void;

    /** Special Ability Flags 0x2000000 */
    isMediumOverlays(): boolean;

    setMediumOverlays(set: boolean): void;

    /** Special Ability Flags 0x4000000 */
    isLargeOverlays(): boolean;

    setLargeOverlays(set: boolean): void;

    /** Special Ability Flags 0x8000000 */
    isCanMove(): boolean;

    setCanMove(set: boolean): void;

    /** Special Ability Flags 0x10000000 */
    isCanTurn(): boolean;

    setCanTurn(set: boolean): void;

    /** Special Ability Flags 0x20000000 */
    isInvincible(): boolean;

    setInvincible(set: boolean): void;

    /** Special Ability Flags 0x40000000 */
    isMechanical(): boolean;

    setMechanical(set: boolean): void;

    /** Target Acquisition Range */
    getTargetAcquisitionRange(): number;

    setTargetAcquisitionRange(value: number): void;

    /** Sight Range */
    getSightRange(): number;

    setSightRange(value: number): void;

    /** Armor Upgrade */
    getArmorUpgrade(): UpgradeType | null;

    setArmorUpgrade(upgradeType: UpgradeType | null): void;

    /** Unit Size */
    getSizeClass(): number;

    setSizeClass(value: number): void;

    /** Armor */
    getArmor(): number;

    setArmor(value: number): void;

    /** Right-click Action */
    getRightClickAction(): number;

    setRightClickAction(value: number): void;

    /** Ready Sound (unit types 0 to 105) */
    getReadySound(): number;

    setReadySound(value: number): void;

    /** What Sound Start */
    getWhatSoundStart(): number;

    setWhatSoundStart(value: number): void;

    /** What Sound End */
    getWhatSoundEnd(): number;

    setWhatSoundEnd(value: number): void;

    /** Piss Sound Start (unit types 0 to 105) */
    getAnnoyedSoundStart(): number;

    setAnnoyedSoundStart(value: number): void;

    /** Piss Sound End (unit types 0 to 105) */
    getAnnoyedSoundEnd(): number;

    setAnnoyedSoundEnd(value: number): void;

    /** Yes Sound Start (unit types 0 to 105) */
    getYesSoundStart(): number;

    setYesSoundStart(value: number): void;

    /** Yes Sound End (unit types 0 to 105) */
    getYesSoundEnd(): number;

    setYesSoundEnd(value: number): void;

    /** Placement Box Width */
    getPlacementWidth(): number;

    setPlacementWidth(value: number): void;

    /** Placement Box Height */
    getPlacementHeight(): number;

    setPlacementHeight(value: number): void;

    /** Addon Horizontal (unit types 106 to 201) */
    getAddonHorizontalOffset(): number;

    setAddonHorizontalOffset(value: number): void;

    /** Addon Vertical (unit types 106 to 201) */
    getAddonVerticalOffset(): number;

    setAddonVerticalOffset(value: number): void;

    /** Unit Size Left */
    getDimensionLeft(): number;

    setDimensionLeft(value: number): void;

    /** Unit Size Up */
    getDimensionUp(): number;

    setDimensionUp(value: number): void;

    /** Unit Size Right */
    getDimensionRight(): number;

    setDimensionRight(value: number): void;

    /** Unit Size Down */
    getDimensionDown(): number;

    setDimensionDown(value: number): void;

    /** Portrait */
    getPortrait(): number;

    setPortrait(value: number): void;

    /** Mineral Cost */
    getMineralCost(): number;

    setMineralCost(value: number): void;

    /** Vespene Cost */
    getGasCost(): number;

    setGasCost(value: number): void;

    /** Build Time */
    getBuildTime(): number;

    setBuildTime(value: number): void;

    /** Requirement Index */
    getRequirementIndex(): number;

    setRequirementIndex(value: number): void;

    /** StarEdit Group Flags 0x1 */
    isGroupZerg(): boolean;

    setGroupZerg(set: boolean): void;

    /** StarEdit Group Flags 0x2 */
    isGroupTerran(): boolean;

    setGroupTerran(set: boolean): void;

    /** StarEdit Group Flags 0x4 */
    isGroupProtoss(): boolean;

    setGroupProtoss(set: boolean): void;

    /** StarEdit Group Flags 0x8 */
    isGroupMen(): boolean;

    setGroupMen(set: boolean): void;

    /** StarEdit Group Flags 0x10 */
    isGroupBuilding(): boolean;

    setGroupBuilding(set: boolean): void;

    /** StarEdit Group Flags 0x20 */
    isGroupFactory(): boolean;

    setGroupFactory(set: boolean): void;

    /** StarEdit Group Flags 0x40 */
    isGroupIndependent(): boolean;

    setGroupIndependent(set: boolean): void;

    /** StarEdit Group Flags 0x80 */
    isGroupNeutral(): boolean;

    setGroupNeutral(set: boolean): void;

    /** Supply Provided */
    getSupplyProvided(): number;

    setSupplyProvided(value: number): void;

    /** Supply Required */
    getSupplyRequired(): number;

    setSupplyRequired(value: number): void;

    /** Space Required */
    getSpaceRequired(): number;

    setSpaceRequired(value: number): void;

    /** Space Provided */
    getSpaceProvided(): number;

    setSpaceProvided(value: number): void;

    /** Build Score */
    getBuildScore(): number;

    setBuildScore(value: number): void;

    /** Destroy Score */
    getDestroyScore(): number;

    setDestroyScore(value: number): void;

    /** Unit Map String */
    getMapStringIndex(): number;

    setMapStringIndex(value: number): void;

    /** Broodwar Unit Flag */
    getBroodWarFlag(): number;

    setBroodWarFlag(value: number): void;

    /** StarEdit Availability Flags 0x1 */
    isAvailabilityNonNeutral(): boolean;

    setAvailabilityNonNeutral(set: boolean): void;

    /** StarEdit Availability Flags 0x2 */
    isAvailabilityUnitListing(): boolean;

    setAvailabilityUnitListing(set: boolean): void;

    /** StarEdit Availability Flags 0x200 */
    isAvailabilityExpansionOnly(): boolean;

    setAvailabilityExpansionOnly(set: boolean): void;
}

/**
 * The weapons.dat values of the weapon types: raw values of the current game.
 * <p>
 * The set functions change the game and work in shared code only. They throw a RangeError where the lower layer's
 * set functions do ({@link CoreDatWeapons}).
 */
interface WeaponTypeData {
    /** Label */
    getLabel(): number;

    setLabel(value: number): void;

    /** Sprite */
    getFlingyType(): FlingyType;

    setFlingyType(flingyType: FlingyType): void;

    /** Special Attack */
    getSpecialAttack(): number;

    setSpecialAttack(value: number): void;

    /** Target Flags 0x1 */
    isTargetAir(): boolean;

    setTargetAir(set: boolean): void;

    /** Target Flags 0x2 */
    isTargetGround(): boolean;

    setTargetGround(set: boolean): void;

    /** Target Flags 0x4 */
    isTargetMechanical(): boolean;

    setTargetMechanical(set: boolean): void;

    /** Target Flags 0x8 */
    isTargetOrganic(): boolean;

    setTargetOrganic(set: boolean): void;

    /** Target Flags 0x10 */
    isTargetNonBuilding(): boolean;

    setTargetNonBuilding(set: boolean): void;

    /** Target Flags 0x20 */
    isTargetNonRobotic(): boolean;

    setTargetNonRobotic(set: boolean): void;

    /** Target Flags 0x40 */
    isTargetTerrain(): boolean;

    setTargetTerrain(set: boolean): void;

    /** Target Flags 0x80 */
    isTargetOrganicOrMechanical(): boolean;

    setTargetOrganicOrMechanical(set: boolean): void;

    /** Target Flags 0x100 */
    isTargetOwn(): boolean;

    setTargetOwn(set: boolean): void;

    /** Minimum Range */
    getMinimumRange(): number;

    setMinimumRange(value: number): void;

    /** Maximum Range */
    getMaximumRange(): number;

    setMaximumRange(value: number): void;

    /** Upgrade Group */
    getDamageUpgrade(): UpgradeType | null;

    setDamageUpgrade(upgradeType: UpgradeType | null): void;

    /** Damage Type */
    getDamageType(): number;

    setDamageType(value: number): void;

    /** Weapon Behavior */
    getBehavior(): number;

    setBehavior(value: number): void;

    /** Remove After */
    getLifetime(): number;

    setLifetime(value: number): void;

    /** Explosion Type */
    getExplosionType(): number;

    setExplosionType(value: number): void;

    /** Inner Splash Radius */
    getInnerSplashRadius(): number;

    setInnerSplashRadius(value: number): void;

    /** Medium Splash Radius */
    getMediumSplashRadius(): number;

    setMediumSplashRadius(value: number): void;

    /** Outer Splash Radius */
    getOuterSplashRadius(): number;

    setOuterSplashRadius(value: number): void;

    /** Damage Amount */
    getDamageAmount(): number;

    setDamageAmount(value: number): void;

    /** Damage Bonus */
    getDamageBonus(): number;

    setDamageBonus(value: number): void;

    /** Weapon Cooldown */
    getCooldown(): number;

    setCooldown(value: number): void;

    /** Damage Factor */
    getDamageFactor(): number;

    setDamageFactor(value: number): void;

    /** Attack Angle */
    getAttackAngle(): number;

    setAttackAngle(value: number): void;

    /** Launch Spin */
    getLaunchSpin(): number;

    setLaunchSpin(value: number): void;

    /** Forward Offset */
    getForwardOffset(): number;

    setForwardOffset(value: number): void;

    /** Upward Offset */
    getUpwardOffset(): number;

    setUpwardOffset(value: number): void;

    /** Target Error Message */
    getTargetErrorMessage(): number;

    setTargetErrorMessage(value: number): void;

    /** Icon */
    getIcon(): number;

    setIcon(value: number): void;
}

/**
 * The flingy.dat values of the flingy types: raw values of the current game.
 * <p>
 * The set functions change the game and work in shared code only. They throw a RangeError where the lower layer's
 * set functions do ({@link CoreDatFlingy}).
 */
interface FlingyTypeData {
    /** Sprite */
    getSprite(): SpriteType;

    setSprite(spriteType: SpriteType): void;

    /** Top Speed */
    getTopSpeed(): number;

    setTopSpeed(value: number): void;

    /** Acceleration */
    getAcceleration(): number;

    setAcceleration(value: number): void;

    /** Halt Distance */
    getHaltDistance(): number;

    setHaltDistance(value: number): void;

    /** Turn Rate */
    getTurnRate(): number;

    setTurnRate(value: number): void;

    /** Unused */
    getUnused(): number;

    setUnused(value: number): void;

    /** Movement Control */
    getMovementControl(): number;

    setMovementControl(value: number): void;
}

/**
 * The sprites.dat values of the sprite types: raw values of the current game.
 * <p>
 * The set functions change the game and work in shared code only. They throw a RangeError where the lower layer's
 * set functions do ({@link CoreDatSprites}).
 */
interface SpriteTypeData {
    /** Image (below 999) */
    getImage(): ImageType;

    setImage(imageType: ImageType): void;

    /** Health Bar (sprite types 130 to 516) */
    getHealthBarWidth(): number;

    setHealthBarWidth(value: number): void;

    /** Unused */
    getUnused(): number;

    setUnused(value: number): void;

    /** Is Visible */
    getVisible(): number;

    setVisible(value: number): void;

    /** Selection Circle (sprite types 130 to 516, below 10) */
    getSelectionCircle(): number;

    setSelectionCircle(value: number): void;

    /** Y Position (sprite types 130 to 516) */
    getSelectionCircleOffset(): number;

    setSelectionCircleOffset(value: number): void;
}

/**
 * The images.dat values of the image types: raw values of the current game.
 * <p>
 * The set functions change the game and work in shared code only. They throw a RangeError where the lower layer's
 * set functions do ({@link CoreDatImages}).
 */
interface ImageTypeData {
    /** GRP File (read only) */
    getGraphicsFile(): number;

    /** Graphic Turns */
    getGraphicTurns(): number;

    setGraphicTurns(value: number): void;

    /** Clickable */
    getClickable(): number;

    setClickable(value: number): void;

    /** Use Full Iscript */
    getFullIscript(): number;

    setFullIscript(value: number): void;

    /** Draw If Cloaked */
    getDrawIfCloaked(): number;

    setDrawIfCloaked(value: number): void;

    /** Draw Function (below 18) */
    getDrawFunction(): number;

    setDrawFunction(value: number): void;

    /** Color Shift */
    getColorShift(): number;

    setColorShift(value: number): void;

    /** Iscript (below 411) */
    getIscript(): number;

    setIscript(value: number): void;

    /** Shield Overlay (read only) */
    getShieldOverlay(): number;

    /** Attack Overlay (read only) */
    getAttackOverlay(): number;

    /** Damage Overlay (read only) */
    getDamageOverlay(): number;

    /** Special Overlay (read only) */
    getSpecialOverlay(): number;

    /** Landing Dust Overlay (read only) */
    getLandingDustOverlay(): number;

    /** Liftoff Dust Overlay (read only) */
    getLiftoffDustOverlay(): number;
}

/**
 * The upgrades.dat values of the upgrade types: raw values of the current game.
 * <p>
 * The set functions change the game and work in shared code only. They throw a RangeError where the lower layer's
 * set functions do ({@link CoreDatUpgrades}).
 */
interface UpgradeTypeData {
    /** Ore Cost */
    getMineralCost(): number;

    setMineralCost(value: number): void;

    /** Ore Cost Factor */
    getMineralCostFactor(): number;

    setMineralCostFactor(value: number): void;

    /** Gas Cost */
    getGasCost(): number;

    setGasCost(value: number): void;

    /** Gas Cost Factor */
    getGasCostFactor(): number;

    setGasCostFactor(value: number): void;

    /** Time Cost */
    getResearchTime(): number;

    setResearchTime(value: number): void;

    /** Time Cost Factor */
    getResearchTimeFactor(): number;

    setResearchTimeFactor(value: number): void;

    /** DatReqs Index */
    getRequirementIndex(): number;

    setRequirementIndex(value: number): void;

    /** Icon */
    getIcon(): number;

    setIcon(value: number): void;

    /** Label */
    getLabel(): number;

    setLabel(value: number): void;

    /** Race */
    getRace(): number;

    setRace(value: number): void;

    /** Max Repeats */
    getMaximumLevel(): number;

    setMaximumLevel(value: number): void;

    /** BroodWar */
    getBroodWarFlag(): number;

    setBroodWarFlag(value: number): void;
}

/**
 * The techdata.dat values of the tech types: raw values of the current game.
 * <p>
 * The set functions change the game and work in shared code only. They throw a RangeError where the lower layer's
 * set functions do ({@link CoreDatTechdata}).
 */
interface TechTypeData {
    /** Ore Cost */
    getMineralCost(): number;

    setMineralCost(value: number): void;

    /** Gas Cost */
    getGasCost(): number;

    setGasCost(value: number): void;

    /** Time Cost */
    getResearchTime(): number;

    setResearchTime(value: number): void;

    /** Energy */
    getEnergyCost(): number;

    setEnergyCost(value: number): void;

    /** DatReqs Index */
    getResearchRequirementIndex(): number;

    setResearchRequirementIndex(value: number): void;

    /** UseReqs Index */
    getUseRequirementIndex(): number;

    setUseRequirementIndex(value: number): void;

    /** Icon */
    getIcon(): number;

    setIcon(value: number): void;

    /** Label */
    getLabel(): number;

    setLabel(value: number): void;

    /** Race */
    getRace(): number;

    setRace(value: number): void;

    /** Researched */
    getResearchedFlag(): number;

    setResearchedFlag(value: number): void;

    /** BroodWar */
    getBroodWarFlag(): number;

    setBroodWarFlag(value: number): void;
}

/**
 * The orders.dat values of the orders: raw values of the current game.
 * <p>
 * The set functions change the game and work in shared code only. They throw a RangeError where the lower layer's
 * set functions do ({@link CoreDatOrders}).
 */
interface OrderData {
    /** Label */
    getLabel(): number;

    setLabel(value: number): void;

    /** Use Weapon Targeting */
    getUseWeaponTargeting(): number;

    setUseWeaponTargeting(value: number): void;

    /** Unused1 */
    getUnused1(): number;

    setUnused1(value: number): void;

    /** Unused2 */
    getUnused2(): number;

    setUnused2(value: number): void;

    /** Subunit Inheritance */
    getSubunitInheritance(): number;

    setSubunitInheritance(value: number): void;

    /** Unused3 */
    getUnused3(): number;

    setUnused3(value: number): void;

    /** Can Be Interrupted */
    getCanBeInterrupted(): number;

    setCanBeInterrupted(value: number): void;

    /** Can Be Lifted */
    getCanBeLifted(): number;

    setCanBeLifted(value: number): void;

    /** Can Be Queued */
    getCanBeQueued(): number;

    setCanBeQueued(value: number): void;

    /** Disabling Keeps Target */
    getDisablingKeepsTarget(): number;

    setDisablingKeepsTarget(value: number): void;

    /** Can Be Obstructed */
    getCanBeObstructed(): number;

    setCanBeObstructed(value: number): void;

    /** Fleeable */
    getFleeable(): number;

    setFleeable(value: number): void;

    /** Unused4 */
    getUnused4(): number;

    setUnused4(value: number): void;

    /** Weapon */
    getWeapon(): WeaponType | null;

    setWeapon(weaponType: WeaponType | null): void;

    /** Energy */
    getTechnology(): TechType | null;

    setTechnology(techType: TechType | null): void;

    /** Animation */
    getAnimation(): number;

    setAnimation(value: number): void;

    /** Highlight */
    getHighlight(): number;

    setHighlight(value: number): void;

    /** DatReqs Index */
    getRequirementIndex(): number;

    setRequirementIndex(value: number): void;

    /** Obscured */
    getObscuredOrder(): Order | null;

    setObscuredOrder(order: Order | null): void;
}

// END GENERATED DAT

/**
 * A unit type (units.dat); its DAT values are those of {@link UnitTypeData}.
 */
interface UnitType extends GameType, UnitTypeData {
    /**
     * @returns the shown maximum hit points: {@link UnitTypeData.getHitPoints} divided by 256
     */
    getMaxHp(): number;

    /**
     * Sets the shown maximum hit points: {@link UnitTypeData.setHitPoints} with the value times 256, rounded. Shared code only.
     *
     * @param hp the shown hit points
     */
    setMaxHp(hp: number): void;

    /**
     * @returns the maximum shields, the same as {@link UnitTypeData.getShieldAmount}
     */
    getMaxShields(): number;

    /**
     * Sets the maximum shields, the same as {@link UnitTypeData.setShieldAmount}. Shared code only.
     *
     * @param shields the shown shields
     */
    setMaxShields(shields: number): void;

    /**
     * Shows a name instead of the type's stat_txt name (tooltips, the selection, messages), also over the map's name of the type. Shared code
     * sets it on every player's game, local code only on this machine.
     *
     * @param text the name, or null to show the game's name again
     */
    setName(text: string | null): void;

    /**
     * @param air true for the strength against air targets, false against ground targets
     * @returns the strength of the unit type for the computer players
     */
    getAiStrength(air: boolean): number;

    /**
     * Sets the strength of the unit type against air or ground targets for the computer players. Shared code only.
     *
     * @param air      true for the strength against air targets, false against ground targets
     * @param strength the strength, at least 0
     * @throws RangeError if the strength is negative
     */
    setAiStrength(air: boolean, strength: number): void;
}

/**
 * A weapon type (weapons.dat); its DAT values are those of {@link WeaponTypeData}.
 */
interface WeaponType extends GameType, WeaponTypeData {
}

/**
 * A flingy type (flingy.dat), named after the sprite type of its sprite; its DAT values are those of {@link FlingyTypeData}.
 */
interface FlingyType extends GameType, FlingyTypeData {
}

/**
 * A sprite type (sprites.dat), named after the image type of its image; its DAT values are those of {@link SpriteTypeData}.
 */
interface SpriteType extends GameType, SpriteTypeData {
}

/**
 * An upgrade type (upgrades.dat); its DAT values are those of {@link UpgradeTypeData}.
 */
interface UpgradeType extends GameType, UpgradeTypeData {
}

/**
 * A technology (techdata.dat); its DAT values are those of {@link TechTypeData}.
 */
interface TechType extends GameType, TechTypeData {
}

/**
 * An order (orders.dat); its DAT values are those of {@link OrderData}.
 */
interface Order extends GameType, OrderData {
}

/**
 * An image type (images.dat), named after its GRP path in images.tbl ("unit\terran\marine.grp" is UnitTerranMarine); its DAT values are those
 * of {@link ImageTypeData}.
 */
interface ImageType extends GameType, ImageTypeData {
    /**
     * @returns the image type whose HD anim this image type draws (images.rel), or this image type when it has its own anim
     */
    getAnimImageType(): ImageType;

    /**
     * @returns the damage overlay byte: twice the flame positions of the image's damage overlay, which set a unit's damage states
     */
    getDamageOverlayCount(): number;

    /**
     * Sets the damage overlay byte. Shared code only.
     *
     * @param count the byte, 0 to 255
     * @throws RangeError if the count is outside 0 to 255
     */
    setDamageOverlayCount(count: number): void;
}

/**
 * A key by its Windows virtual-key code, which is its id.
 */
interface Key extends GameType {
}

/**
 * A mouse button: 0 left, 1 right, 2 middle.
 */
interface MouseButton extends GameType {
}

/**
 * A unit group of the trigger conditions and actions (any unit, men, buildings, factories), which counts where a unit type does.
 */
interface UnitGroup extends GameType {
}

/**
 * A score type of the Score condition and the Set Score action, 0 (total) to 7 (custom).
 */
interface ScoreType extends GameType {
}

/**
 * A resource type: 0 minerals, 1 gas, 2 minerals and gas, 3 supply (the supply icon of the console only).
 */
interface ResourceType extends GameType {
}

/**
 * An alliance status: 0 enemy, 1 ally, 2 allied victory.
 */
interface Alliance extends GameType {
}

/**
 * A game speed, which names an entry of the frame duration table: 0 slowest to 6 fastest.
 */
interface GameSpeed extends GameType {
    /**
     * @returns the milliseconds of a frame at the speed
     */
    getFrameDuration(): number;

    /**
     * Sets the milliseconds of a frame at the speed, as an EUD write does: the game takes it at once unless the game name overrides the
     * speed; the turn timeouts of a network game keep their last durations. Shared code only.
     *
     * @param milliseconds the frame duration, at least 0
     * @throws RangeError if the duration is negative
     */
    setFrameDuration(milliseconds: number): void;
}

/**
 * A cheat; its id is its bit of the cheat flags.
 */
interface Cheat extends GameType {
}

/**
 * A game type of the game header (Melee, Use Map Settings ...); its id is the header value.
 */
interface GameMode extends GameType {
}

/**
 * A unit size of units.dat: 0 independent, 1 small, 2 medium, 3 large.
 */
interface UnitSize extends GameType {
}

/**
 * A damage type of weapons.dat: 0 independent, 1 explosive, 2 concussive, 3 normal, 4 ignore armor.
 */
interface DamageType extends GameType {
    /**
     * @param size the unit size of the target
     * @returns the factor that scales the damage against the size, 1 for full damage
     */
    getMultiplier(size: UnitSize): number;

    /**
     * Sets the factor that scales the damage against the size; it is stored in 1/256, rounded. Shared code only.
     *
     * @param size   the unit size of the target
     * @param factor the factor, at least 0; 1 for full damage
     * @throws RangeError if the factor is negative
     */
    setMultiplier(size: UnitSize, factor: number): void;
}

/**
 * An elevation flag of a location; its id is its bit of the location flags.
 */
interface LocationFlag extends GameType {
}

/**
 * A map position in pixels; positions are immutable.
 */
interface Position {
    /**
     * @returns the x in map pixels
     */
    getX(): number;

    /**
     * @returns the y in map pixels
     */
    getY(): number;
}

/**
 * Creates positions.
 */
interface PositionStatic {
    /**
     * @param x the x in map pixels
     * @param y the y in map pixels
     * @returns the position
     */
    of(x: number, y: number): Position;
}

/**
 * Creates positions: {@code Position.of(x, y)}.
 */
declare const Position: PositionStatic;

/**
 * Converts durations to frames, rounded to the nearest frame (halves up).
 */
interface TimeStatic {
    /**
     * @returns the frames of seconds of the trigger clock (16 frames a second), the unit of the countdown timer and the elapsed time
     */
    fromSeconds(seconds: number): number;

    /**
     * @returns the frames of minutes of the trigger clock
     */
    fromMinutes(minutes: number): number;

    /**
     * @returns the frames of real milliseconds at the fastest speed (42 ms a frame)
     */
    fromMilliseconds(milliseconds: number): number;
}

/**
 * Converts durations to frames, for {@link Game.every} and {@link Game.after}.
 */
declare const Time: TimeStatic;

/**
 * A unit filter: a function that returns true for the units to take, or a unit type and an owner, each optional.
 */
type UnitFilter = ((unit: Unit) => boolean) | { type?: UnitType; owner?: Player };

/**
 * A unit: one object while the unit lives. Every method but {@link Unit.exists} and {@link Unit.getId} throws an Error once the unit is gone.
 * Hit points, shields and energy are the shown values (the raw values divided by 256); setting them rounds to 1/256. A negative amount throws
 * a RangeError.
 */
interface Unit {
    /**
     * @returns the unit's slot in the unit pool, from 1, which the lower layer {@link core} takes
     */
    getId(): number;

    /**
     * @returns true while the unit lives, false once it is gone and its slot holds another unit or none
     */
    exists(): boolean;

    /**
     * @returns the unit type
     */
    getType(): UnitType;

    /**
     * @returns the player who owns the unit
     */
    getOwner(): Player;

    /**
     * Gives the unit to a player as Give Units to Player does. Shared code only.
     *
     * @returns false when the routine refuses
     */
    setOwner(player: Player): boolean;

    /**
     * @returns the x of the unit's center in map pixels
     */
    getX(): number;

    /**
     * @returns the y of the unit's center in map pixels
     */
    getY(): number;

    /**
     * @returns the unit's center in map pixels
     */
    getPosition(): Position;

    /**
     * Moves the unit as Move Unit does. Shared code only.
     *
     * @returns false when the routine refuses
     */
    setPosition(position: Position): boolean;

    /**
     * @returns the shown hit points
     */
    getHp(): number;

    /**
     * Sets the hit points through the hit point routine, kept above 0 and at most the maximum. Shared code only.
     */
    setHp(hp: number): void;

    /**
     * @returns the shown maximum hit points of the unit's type in units.dat
     */
    getMaxHp(): number;

    /**
     * @returns the shields, 0 for a unit without shields
     */
    getShields(): number;

    /**
     * Sets the shields, at most the maximum. Shared code only.
     *
     * @returns false for a unit without shields
     */
    setShields(shields: number): boolean;

    /**
     * @returns the maximum shields of the unit's type in units.dat, 0 for a type without shields
     */
    getMaxShields(): number;

    /**
     * @returns the energy, 0 for a unit without energy
     */
    getEnergy(): number;

    /**
     * Sets the energy, at most the maximum. Shared code only.
     *
     * @returns false for a unit without energy
     */
    setEnergy(energy: number): boolean;

    /**
     * @returns the maximum energy after the energy upgrades of the unit's owner, 0 for a unit without energy
     */
    getMaxEnergy(): number;

    /**
     * Sets the hit points to a percentage of the maximum, as Modify Unit Hit Points does. Shared code only.
     */
    setHpPercent(percent: number): void;

    /**
     * Sets the shields to a percentage of the maximum, as Modify Unit Shield Points does. Shared code only.
     *
     * @returns false when the routine refuses
     */
    setShieldsPercent(percent: number): boolean;

    /**
     * Sets the energy to a percentage of the maximum, as Modify Unit Energy does. Shared code only.
     *
     * @returns false when the routine refuses
     */
    setEnergyPercent(percent: number): boolean;

    /**
     * @returns the current order
     */
    getOrder(): Order;

    /**
     * @returns the target unit of the current order, or null for none
     */
    getTarget(): Unit | null;

    /**
     * @returns the target position of the current order
     */
    getTargetPosition(): Position;

    /**
     * @returns true once the unit is completed
     */
    isCompleted(): boolean;

    /**
     * @returns true while the unit is cloaked, passive cloaking included
     */
    isCloaked(): boolean;

    /**
     * @returns true while the unit is burrowed
     */
    isBurrowed(): boolean;

    /**
     * @returns true while the unit is invincible
     */
    isInvincible(): boolean;

    /**
     * @returns the image type of the unit's main image
     */
    getImageType(): ImageType;

    /**
     * Makes the unit invincible or not, as Set Invincibility does. Shared code only.
     */
    setInvincible(invincible: boolean): void;

    /**
     * @returns true for a disabled doodad, or a unit under Lockdown, Stasis Field or Maelstrom
     */
    isDisabled(): boolean;

    /**
     * Enables a doodad as Set Doodad State does. Shared code only.
     */
    enableDoodad(): void;

    /**
     * Disables a doodad as Set Doodad State does. Shared code only.
     */
    disableDoodad(): void;

    /**
     * @returns the resource amount of a mineral field or geyser
     */
    getResources(): number;

    /**
     * Sets the resource amount as Modify Unit Resource Amount does. Shared code only.
     *
     * @param amount the amount, 0 to 65535
     * @returns false for a unit that holds no resources
     * @throws RangeError if the amount is outside 0 to 65535
     */
    setResources(amount: number): boolean;

    /**
     * @returns the Interceptors or Scarabs of a Carrier or Reaver, inside and outside the hangar
     */
    getHangarCount(): number;

    /**
     * Builds up to count Interceptors or Scarabs while the hangar takes another one, as Modify Unit Hanger Count does. Shared code only.
     *
     * @returns false for a unit without a hangar
     */
    addHangarCount(count: number): boolean;

    /**
     * Kills the unit. Without a source it dies as Kill Unit does, credited to nobody; with a source it dies through the damage routine (see
     * {@link Unit.damage}), so that the death event names the killer and the kill counts for the source. Shared code only.
     *
     * @param source the killer: a unit (its owner is the killing player) or a player; null or left out for none
     * @returns false when the routine refuses, as for a subunit
     */
    kill(source?: Unit | Player | null): boolean;

    /**
     * Takes hit points off the unit as a hit does after armor and shields; a unit whose hit points run out dies. With a source the death event
     * names the killer ({@link UnitDiedEvent.getKiller}, {@link UnitDiedEvent.getKillerPlayer}), the source unit's kill count grows against an
     * enemy, the source player's kill and score counters count the unit, and a source unit is revealed to this unit's owner as an attacker is.
     * Shared code only.
     *
     * @param hp     the hit points to take off, at least 0
     * @param source the attacker: a unit (its owner is the attacking player) or a player; null or left out for none
     * @returns false for a subunit, which stays with its unit
     */
    damage(hp: number, source?: Unit | Player | null): boolean;

    /**
     * Removes the unit as Remove Unit does. Shared code only.
     *
     * @returns false when the routine refuses
     */
    remove(): boolean;

    /**
     * Issues an order to a target unit or a position; without a target the unit's own position is the target. An order to a position without
     * {queued: true} runs as the Order action does; a queued order or one to a unit goes through the order system after the order requirement
     * test, and with {queued: true} it goes behind the unit's queue when the order takes one, as a queued command does. Shared code only.
     *
     * @throws RangeError if the order cannot be issued
     */
    issueOrder(order: Order, target?: Unit | Position | null, options?: IssueOrderOptions): void;

    /**
     * @returns the orders waiting behind the current one, first to last
     */
    getOrderQueue(): QueuedOrder[];

    /**
     * @returns the secondary order
     */
    getSecondaryOrder(): Order;

    /**
     * @returns the state byte of the secondary order
     */
    getSecondaryOrderState(): number;

    /**
     * Sets the secondary order as the game does: a changed order starts from its first state and clears the unit in production. Shared code
     * only.
     */
    setSecondaryOrder(order: Order): void;

    /**
     * @returns the unit's command card button set, or null for a set outside the table
     */
    getButtonSet(): ButtonSet | null;

    /**
     * Sets the unit's command card button set; the game sets it again when the unit morphs, completes or burrows. Shared code only.
     *
     * @param set a button set, a unit type (its set) or a set id
     * @throws TypeError if the set is none of these or the id is outside the table
     */
    setButtonSet(set: ButtonSet | UnitType | number): void;

    /**
     * @returns the shown hit points a unit under construction gains each frame
     */
    getHpGain(): number;

    /**
     * Sets the shown hit points a unit under construction gains each frame, up to 65535/256. Shared code only.
     */
    setHpGain(hp: number): void;

    /**
     * @returns the shown shields a unit under construction gains each frame
     */
    getShieldGain(): number;

    /**
     * Sets the shown shields a unit under construction gains each frame, up to 65535/256. Shared code only.
     */
    setShieldGain(shields: number): void;

    /**
     * @returns the whole hit points the unit had before it morphed, which a cancelled Zerg building gives back
     */
    getPreviousHp(): number;

    /**
     * @returns true when the player sees the unit: in the player's sight and, when cloaked or burrowed, detected
     */
    isVisibleTo(player: Player): boolean;

    /**
     * @returns true when the player detects the unit
     */
    isDetectedBy(player: Player): boolean;

    /**
     * @returns the players whose sight covers the unit
     */
    getVisibleTo(): Player[];

    /**
     * @param air true for the strength against air targets, false against ground targets
     * @returns the unit's strength for the computer players
     */
    getAiStrength(air: boolean): number;

    /**
     * The unit's status timers and its remove timer; one unit always has the same object.
     */
    readonly timers: UnitTimers;

    /**
     * @returns the shown hit points of the unit's Defensive Matrix
     */
    getDefensiveMatrixHp(): number;

    /**
     * Sets the shown hit points of the unit's Defensive Matrix, up to 65535/256, as an EUD write stores them. Shared code only.
     */
    setDefensiveMatrixHp(hp: number): void;

    /**
     * @returns the number of Acid Spores on the unit
     */
    getAcidSporeCount(): number;

    /**
     * Sets the number of Acid Spores on the unit, 0 to 255: a count shows its overlay, 0 removes the spores. Shared code only.
     *
     * @throws RangeError if the count is outside 0 to 255
     */
    setAcidSporeCount(count: number): void;

    /**
     * @returns true when the player's Parasite is on the unit
     */
    isParasitedBy(player: Player): boolean;

    /**
     * Puts the player's Parasite on the unit or takes it off. Shared code only.
     */
    setParasitedBy(player: Player, parasited: boolean): void;

    /**
     * @returns true when an Optical Flare blinded the unit
     */
    isBlind(): boolean;

    /**
     * @returns true when the player's Optical Flare blinded the unit
     */
    isBlindedBy(player: Player): boolean;

    /**
     * Sets or clears the player's blindness bit of the unit. Shared code only.
     */
    setBlindedBy(player: Player, blinded: boolean): void;

    /**
     * @returns the frames until the ground weapon can fire again
     */
    getGroundCooldown(): number;

    /**
     * Sets the frames until the ground weapon can fire again, 0 to 255. Shared code only.
     *
     * @throws RangeError if the frames are outside 0 to 255
     */
    setGroundCooldown(frames: number): void;

    /**
     * @returns the frames until the air weapon can fire again
     */
    getAirCooldown(): number;

    /**
     * Sets the frames until the air weapon can fire again, 0 to 255. Shared code only.
     *
     * @throws RangeError if the frames are outside 0 to 255
     */
    setAirCooldown(frames: number): void;

    /**
     * @returns the frames until the unit can cast a spell again
     */
    getSpellCooldown(): number;

    /**
     * Sets the frames until the unit can cast a spell again, 0 to 255. Shared code only.
     *
     * @throws RangeError if the frames are outside 0 to 255
     */
    setSpellCooldown(frames: number): void;

    /**
     * @returns the unit's kill count
     */
    getKillCount(): number;

    /**
     * Sets the unit's kill count, 0 to 255. Shared code only.
     *
     * @throws RangeError if the kills are outside 0 to 255
     */
    setKillCount(kills: number): void;

    /**
     * @returns the unit types in the build queue, from the current one
     */
    getBuildQueue(): UnitType[];

    /**
     * @returns the frames left of the current construction or training
     */
    getRemainingBuildTime(): number;

    /**
     * Sets the frames left of the current construction or training, 0 to 65535. Shared code only.
     *
     * @throws RangeError if the frames are outside 0 to 65535
     */
    setRemainingBuildTime(frames: number): void;

    /**
     * @returns the frames left of the current research or upgrade
     */
    getRemainingResearchTime(): number;

    /**
     * Sets the frames left of the current research or upgrade, 0 to 65535. Shared code only.
     *
     * @throws RangeError if the frames are outside 0 to 65535
     */
    setRemainingResearchTime(frames: number): void;

    /**
     * @returns the technology in research, or null for none
     */
    getResearch(): TechType | null;

    /**
     * @returns the upgrade in research, or null for none
     */
    getUpgrade(): UpgradeType | null;

    /**
     * @returns the add-on, or null for none
     */
    getAddon(): Unit | null;

    /**
     * @returns the rally point in map pixels
     */
    getRallyPosition(): Position;

    /**
     * Sets the rally point to a position, x and y from 0 to 65535. Shared code only.
     *
     * @returns false for a unit type without a rally point
     * @throws RangeError if a coordinate is outside 0 to 65535
     */
    setRallyPosition(position: Position): boolean;

    /**
     * @returns the rally unit, or null for none
     */
    getRallyUnit(): Unit | null;

    /**
     * Sets the rally unit. Shared code only.
     *
     * @param unit the rally unit, or null for none
     * @returns false for a unit type without a rally point
     */
    setRallyUnit(unit: Unit | null): boolean;

    /**
     * @returns the direction, 0 to 255
     */
    getDirection(): number;

    /**
     * Turns the unit to a direction at once. Shared code only.
     *
     * @param direction the direction, 0 to 255
     * @throws RangeError if the direction is outside 0 to 255
     */
    setDirection(direction: number): void;

    /**
     * @returns the speed in 1/256 pixels per frame
     */
    getCurrentSpeed(): number;

    /**
     * @returns the top speed in 1/256 pixels per frame
     */
    getTopSpeed(): number;

    /**
     * @returns the position the unit moves to
     */
    getMoveTarget(): Position;

    /**
     * Sets the position the unit moves to, x and y from 0 to 65535. Shared code only.
     *
     * @throws RangeError if a coordinate is outside 0 to 65535
     */
    setMoveTarget(position: Position): void;

    /**
     * Changes the unit's type through the morph routine, which keeps the unit, scales its hit points and rebuilds its sprite. Shared code only.
     *
     * @returns false for a subunit, a subunit type, or a type whose turret does not match the unit's
     */
    setType(type: UnitType): boolean;

    /**
     * @returns the unit's sprite, or null for none
     */
    getSprite(): Sprite | null;

    /**
     * Runs the use-weapon routine of the unit scripts: the bullets of the weapon toward the unit's order target, two for a weapon of damage
     * factor 2, none for a weapon without a flingy. Shared code only.
     */
    useWeapon(weaponType: WeaponType): void;

    /**
     * Plays an iscript animation on the images of the unit's sprite, as an order does when its state changes: each image switches to the
     * animation and runs it at once, with the opcodes the game runs (an attack animation attacks the unit's order target). An image whose
     * iscript lacks the animation, and every image while the sprite runs a section that must not be broken, keeps its own. The unit's orders
     * play their own animations again when their state changes. Shared code only.
     *
     * @param animation the animation, Animation.GroundAttackInit to Animation.Enable; Init and Death throw a RangeError
     */
    playAnimation(animation: Animation): void;
}

/**
 * The options of {@link Unit.issueOrder}.
 */
interface IssueOrderOptions {
    /**
     * True to put the order behind the unit's queue, as a queued command does; false by default.
     */
    queued?: boolean;
}

/**
 * An order waiting in a unit's queue ({@link Unit.getOrderQueue}).
 */
interface QueuedOrder {
    /**
     * The order.
     */
    readonly order: Order;
    /**
     * The target unit, or null for none or a unit that is gone.
     */
    readonly target: Unit | null;
    /**
     * The target position.
     */
    readonly position: Position;
    /**
     * The unit type the order builds or trains, or null for none.
     */
    readonly unitType: UnitType | null;
}

/**
 * The timers of a unit ({@link Unit.timers}). The status timers count status steps of eight frames, 0 to 255. Starting the Ensnare, Lockdown,
 * Stasis Field, Maelstrom, Plague or Irradiate timer runs the effect's apply routine (overlay, disabling, slowing) before it takes the value,
 * and 0 runs the effect's end routine. Every method throws an Error once the unit is gone; the setters are shared code only and throw a
 * RangeError for a value outside their range.
 */
interface UnitTimers {
    /**
     * @returns the Stim Packs timer in status steps
     */
    getStim(): number;

    /**
     * Sets the Stim Packs timer in status steps, 0 to 255; starting or ending it refreshes the unit's speed. Shared code only.
     */
    setStim(timer: number): void;

    /**
     * @returns the Ensnare timer in status steps
     */
    getEnsnare(): number;

    /**
     * Sets the Ensnare timer in status steps, 0 to 255. Shared code only.
     */
    setEnsnare(timer: number): void;

    /**
     * @returns the Lockdown timer in status steps
     */
    getLockdown(): number;

    /**
     * Sets the Lockdown timer in status steps, 0 to 255. Shared code only.
     */
    setLockdown(timer: number): void;

    /**
     * @returns the Stasis Field timer in status steps
     */
    getStasis(): number;

    /**
     * Sets the Stasis Field timer in status steps, 0 to 255. Shared code only.
     */
    setStasis(timer: number): void;

    /**
     * @returns the Maelstrom timer in status steps
     */
    getMaelstrom(): number;

    /**
     * Sets the Maelstrom timer in status steps, 0 to 255. Shared code only.
     */
    setMaelstrom(timer: number): void;

    /**
     * @returns the Plague timer in status steps
     */
    getPlague(): number;

    /**
     * Sets the Plague timer in status steps, 0 to 255. Shared code only.
     */
    setPlague(timer: number): void;

    /**
     * @returns the Irradiate timer in status steps
     */
    getIrradiate(): number;

    /**
     * Sets the Irradiate timer in status steps, 0 to 255. Shared code only.
     */
    setIrradiate(timer: number): void;

    /**
     * @returns the Psionic Storm timer
     */
    getStorm(): number;

    /**
     * Sets the Psionic Storm timer, 0 to 255, as an EUD write stores it. Shared code only.
     */
    setStorm(timer: number): void;

    /**
     * @returns the Defensive Matrix timer in status steps
     */
    getDefensiveMatrix(): number;

    /**
     * Sets the Defensive Matrix timer in status steps, 0 to 255: starting it adds the overlays, 0 ends the matrix as its expiry does. Shared
     * code only.
     */
    setDefensiveMatrix(timer: number): void;

    /**
     * @returns the frames until a timed unit (a hallucination, a Broodling) is removed, 0 for an untimed unit
     */
    getRemove(): number;

    /**
     * Sets the frames until the unit is removed, 0 to 65535. Shared code only.
     */
    setRemove(frames: number): void;
}

/**
 * The options of a unit count ({@link PlayerUnits.count}).
 */
interface UnitCountOptions {
    /**
     * Counts only the units at the location; null or none counts everywhere.
     */
    location?: Location | null;
    /**
     * True to count only completed units; false by default.
     */
    completed?: boolean;
}

/**
 * The options of a unit removal ({@link PlayerUnits.kill}, {@link PlayerUnits.remove}).
 */
interface UnitRemovalOptions {
    /**
     * Takes only the units at the location; null or none takes them everywhere.
     */
    location?: Location | null;
    /**
     * The most units to take, at least 1; all without one.
     */
    count?: number;
}

/**
 * A player by slot, from 0 (the game's player 1) to 11; one object per slot. The resource writes leave players 8 to 11 unchanged, as Set
 * Resources does.
 */
interface Player {
    /**
     * @returns the slot, from 0
     */
    getId(): number;

    /**
     * @returns the player's name
     */
    getName(): string;

    /**
     * @returns the race from 0 (Zerg) to 2 (Protoss)
     */
    getRace(): number;

    /**
     * @returns true for a human player
     */
    isHuman(): boolean;

    /**
     * @returns the player's minerals
     */
    getMinerals(): number;

    /**
     * Sets the minerals as Set Resources does, the gathered total too. Shared code only.
     */
    setMinerals(minerals: number): void;

    /**
     * Adds minerals as Set Resources does, to the gathered total too. Shared code only.
     */
    addMinerals(minerals: number): void;

    /**
     * Subtracts minerals as Set Resources does: 0 when the amount is not below the current one. Shared code only.
     */
    subtractMinerals(minerals: number): void;

    /**
     * @returns the player's gas
     */
    getGas(): number;

    /**
     * Sets the gas as Set Resources does, the gathered total too. Shared code only.
     */
    setGas(gas: number): void;

    /**
     * Adds gas as Set Resources does, to the gathered total too. Shared code only.
     */
    addGas(gas: number): void;

    /**
     * Subtracts gas as Set Resources does: 0 when the amount is not below the current one. Shared code only.
     */
    subtractGas(gas: number): void;

    /**
     * Ends the game for the player, one from 0 to 7, as Victory does. Shared code only.
     */
    win(): boolean;

    /**
     * Ends the game for the player, one from 0 to 7, as Defeat does. Shared code only.
     *
     * @returns false after a victory
     */
    lose(): boolean;

    /**
     * Ends the game for the player, one from 0 to 7, as Draw does. Shared code only.
     *
     * @returns false after a victory
     */
    draw(): boolean;

    /**
     * @param race the race from 0 (Zerg) to 2 (Protoss), the player's race without one
     * @returns the shown supply the player's units of the race use
     */
    getSupplyUsed(race?: number): number;

    /**
     * @param race the race from 0 (Zerg) to 2 (Protoss), the player's race without one
     * @returns the shown supply the player's units of the race provide
     */
    getSupplyProvided(race?: number): number;

    /**
     * @param race the race from 0 (Zerg) to 2 (Protoss), the player's race without one
     * @returns the shown supply limit of the race
     */
    getSupplyMax(race?: number): number;

    /**
     * @returns true when this player is allied with the other player
     */
    isAlliedWith(player: Player): boolean;

    /**
     * @returns true when this player is an enemy of the other player
     */
    isEnemyOf(player: Player): boolean;

    /**
     * @returns this player's alliance status toward the other player
     */
    getAlliance(player: Player): Alliance;

    /**
     * Sets the alliance status toward another player, one from 0 to 7, as Set Alliance Status does. Shared code only.
     */
    setAlliance(player: Player, alliance: Alliance): void;

    /**
     * @param resourceType minerals, gas, or minerals and gas
     * @returns the resources as the Accumulate condition reads them
     * @throws RangeError for the supply resource type
     */
    getResources(resourceType: ResourceType): number;

    /**
     * @returns the opponents the Opponents condition counts
     */
    countOpponents(): number;

    /**
     * Starts an AI script, at a location when one is given. Shared code only.
     *
     * @param name the four-character name of the script ("EnBk"), each character up to 0xFF
     * @returns false when the player may not run it
     * @throws RangeError if the name is not four such characters
     */
    runAiScript(name: string, location?: Location | null): boolean;

    /**
     * Sets the player's mission objectives as Set Mission Objectives does. Shared code only.
     */
    setMissionObjectives(text: string): void;

    /**
     * Shows a text on the player's screen as Display Text Message does, when the player is the local player; with {forced: false} only when
     * the subtitle option is on. Shared code only.
     */
    print(text: string, options?: { forced?: boolean }): void;

    /**
     * Pings the minimap at a position as Minimap Ping does, when the player is the local player. Shared code only.
     */
    pingMinimap(position: Position): void;

    /**
     * Plays a sound file of the map archive by its path, as Play WAV does, when the player is the local player. Shared code only.
     */
    playSound(path: string): void;

    /**
     * Shows the talking portrait of a unit type as Talking Portrait does, when the player is the local player. Shared code only.
     *
     * @param frames the duration in frames, at least 0
     * @throws RangeError if the duration is negative
     */
    showTalkingPortrait(unitType: UnitType, frames: number): void;

    /**
     * Mutes or unmutes unit speech as Mute and Unmute Unit Speech do, when the player is the local player. Shared code only.
     */
    setUnitSpeechMuted(muted: boolean): void;

    /**
     * Centers the screen on a position when the player is the local player; the screens of the other players stay. Shared code only.
     */
    centerView(position: Position): void;

    /**
     * Returns the player's selection as every player's game holds it; the selection commands change it.
     *
     * @returns the live units in selection order, empty for players 8 to 11
     */
    getSelection(): Unit[];

    /**
     * Replaces the player's selection as a selection command of the player does, on every player's game at the same frame, and shows it as
     * the local selection when the player is the local player. The command's rules may leave units out: a hidden unit, and in a selection of
     * more than one unit a building, a unit of another player or one that cannot join a group. An empty array clears the selection. Shared
     * code only.
     *
     * @param units at most 12 units that exist
     * @returns true if every unit was selected, false if the rules left some out
     * @throws RangeError for more than 12 units
     */
    setSelection(units: Unit[]): boolean;

    /**
     * The player's upgrade levels by upgrade type; one player always has the same object.
     */
    readonly upgrades: PlayerUpgrades;
    /**
     * The player's technologies by tech type.
     */
    readonly techs: PlayerTechs;
    /**
     * The player's units: listing, counting, killing and removing them, and their production availability.
     */
    readonly units: PlayerUnits;
    /**
     * The player's death counters of the Deaths condition and the Set Deaths action.
     */
    readonly deaths: PlayerDeaths;
    /**
     * The player's kill counters of the Kill condition.
     */
    readonly kills: PlayerKills;
    /**
     * The player's scores of the Score condition and the Set Score action.
     */
    readonly score: PlayerScore;
    /**
     * The player's shared vision and the tiles the player sees.
     */
    readonly vision: PlayerVision;

    /**
     * @returns the minerals of the gathered total of the score
     */
    getGatheredMinerals(): number;

    /**
     * Sets the minerals of the gathered total of the score. Shared code only.
     */
    setGatheredMinerals(minerals: number): void;

    /**
     * @returns the gas of the gathered total of the score
     */
    getGatheredGas(): number;

    /**
     * Sets the gas of the gathered total of the score. Shared code only.
     */
    setGatheredGas(gas: number): void;

    /**
     * @returns the pauses a player from 0 to 7 has left in a network game
     */
    getPausesLeft(): number;

    /**
     * Sets the pauses a player from 0 to 7 has left in a network game, 0 to 255. Shared code only.
     *
     * @throws RangeError if the pauses are outside 0 to 255
     */
    setPausesLeft(pauses: number): void;

    /**
     * @returns the start location, which the computer players and the start screen read, or null for a player without one
     */
    getStartLocation(): Position | null;

    /**
     * Moves the start location, x and y from 0 to 65535. Shared code only.
     *
     * @returns false for a player without a start location
     */
    setStartLocation(position: Position): boolean;

    /**
     * @returns the eight palette indices of the player's unit color row, the remapped colors of a unit frame; the first one is also the HD team color
     */
    getColor(): number[];

    /**
     * Sets the eight palette indices (0 to 255) of the player's unit color row, as an EUD write does; the next drawn frame shows them. Shared
     * code sets them on every player's game, local code only on this machine. A player shown in a custom color of the game keeps it as its HD
     * team color.
     *
     * @throws TypeError if the argument is not an array of eight numbers
     * @throws RangeError if an index is outside 0 to 255
     */
    setColor(paletteIndices: number[]): void;

    /**
     * @returns the palette index of the player's minimap dots
     */
    getMinimapColor(): number;

    /**
     * Sets the palette index (0 to 255) of the player's minimap dots; a player shown in a custom color of the game keeps it on the minimap.
     * Shared code sets it on every player's game, local code only on this machine.
     */
    setMinimapColor(paletteIndex: number): void;
}

/**
 * The vision of a player ({@link Player.vision}).
 */
interface PlayerVision {
    /**
     * @returns the players that see what this player's units see, this player included
     */
    getSharedWith(): Player[];

    /**
     * Sets the players that see what this player's units see, without the vision command's permission test; the player sees its own units'
     * sight only while it is among them. Shared code only.
     */
    setSharedWith(players: Player[]): void;

    /**
     * @returns true when the other player sees what this player's units see
     */
    isSharedWith(player: Player): boolean;

    /**
     * Shares this player's vision with another player or stops sharing it. Shared code only.
     */
    shareWith(player: Player, shared: boolean): void;

    /**
     * @returns true while the player sees the position
     */
    canSee(position: Position): boolean;

    /**
     * @returns true when the player has explored the position
     */
    hasExplored(position: Position): boolean;

    /**
     * Marks the tile at a position explored by the player, still unseen until a unit of the player sees it, or unexplored and unseen. Shared
     * code only.
     *
     * @throws RangeError for a player from 8 to 11, who has no tile vision
     */
    setExplored(position: Position, explored: boolean): void;
}

/**
 * The upgrade levels of a player ({@link Player.upgrades}).
 */
interface PlayerUpgrades {
    /**
     * @returns the upgrade's current level
     */
    getLevel(upgrade: UpgradeType): number;

    /**
     * Sets the level, 0 to 255, and refreshes the upgrade flags of the player's units, as a finished upgrade does. Shared code only.
     */
    setLevel(upgrade: UpgradeType, level: number): void;

    /**
     * @returns the highest level the player may research
     */
    getMaxLevel(upgrade: UpgradeType): number;

    /**
     * Sets the highest level the player may research, 0 to 255. Shared code only.
     */
    setMaxLevel(upgrade: UpgradeType, level: number): void;

    /**
     * @returns true while one of the player's buildings researches the upgrade
     */
    isInProgress(upgrade: UpgradeType): boolean;
}

/**
 * The technologies of a player ({@link Player.techs}).
 */
interface PlayerTechs {
    /**
     * @returns true when the player has researched the technology
     */
    isResearched(tech: TechType): boolean;

    /**
     * Marks the technology researched or not researched. Shared code only.
     */
    setResearched(tech: TechType, researched: boolean): void;

    /**
     * @returns true when the player may research the technology
     */
    isAvailable(tech: TechType): boolean;

    /**
     * Allows or forbids researching the technology. Shared code only.
     */
    setAvailable(tech: TechType, available: boolean): void;

    /**
     * @returns true while one of the player's buildings researches the technology
     */
    isInProgress(tech: TechType): boolean;
}

/**
 * The units of a player ({@link Player.units}).
 */
interface PlayerUnits {
    /**
     * @returns the player's live units, in slot order
     */
    getAll(): Unit[];

    /**
     * Counts the player's units as the Command condition does, or as the Bring condition does with a location.
     */
    count(unit: UnitType | UnitGroup, options?: UnitCountOptions): number;

    /**
     * Kills the player's units as Kill Unit does. Shared code only.
     *
     * @throws RangeError if the count option is not positive
     */
    kill(unit: UnitType | UnitGroup, options?: UnitRemovalOptions): void;

    /**
     * Removes the player's units as Remove Unit does. Shared code only.
     *
     * @throws RangeError if the count option is not positive
     */
    remove(unit: UnitType | UnitGroup, options?: UnitRemovalOptions): void;

    /**
     * @returns the production availability of the map settings; a game without map settings allows every unit type
     */
    isAvailable(unitType: UnitType): boolean;

    /**
     * Sets the production availability of the map settings. Shared code only.
     *
     * @throws TypeError if a type is made unavailable in a game without map settings
     */
    setAvailable(unitType: UnitType, available: boolean): void;
}

/**
 * The death counters of a player by unit type or unit group ({@link Player.deaths}).
 */
interface PlayerDeaths {
    /**
     * @returns the deaths the Deaths condition compares
     */
    get(unit: UnitType | UnitGroup): number;

    /**
     * Sets the deaths as Set Deaths does. Shared code only.
     */
    set(unit: UnitType | UnitGroup, amount: number): void;

    /**
     * Adds to the deaths as Set Deaths does. Shared code only.
     */
    add(unit: UnitType | UnitGroup, amount: number): void;

    /**
     * Subtracts from the deaths as Set Deaths does. Shared code only.
     */
    subtract(unit: UnitType | UnitGroup, amount: number): void;
}

/**
 * The kill counters of a player by unit type or unit group ({@link Player.kills}).
 */
interface PlayerKills {
    /**
     * @returns the kills the Kill condition compares
     */
    get(unit: UnitType | UnitGroup): number;
}

/**
 * The scores of a player by score type ({@link Player.score}); the writes take players 0 to 7.
 */
interface PlayerScore {
    /**
     * @returns the score the Score condition compares
     */
    get(scoreType: ScoreType): number;

    /**
     * Sets the score as Set Score does. Shared code only.
     */
    set(scoreType: ScoreType, amount: number): void;

    /**
     * Adds to the score as Set Score does. Shared code only.
     */
    add(scoreType: ScoreType, amount: number): void;

    /**
     * Subtracts from the score as Set Score does. Shared code only.
     */
    subtract(scoreType: ScoreType, amount: number): void;
}

/**
 * A map location by its 0-based index; one object per location. The bounds are map pixels.
 */
interface Location {
    /**
     * @returns the 0-based index of the location
     */
    getId(): number;

    /**
     * @returns the location's name, or null for a location without one
     */
    getName(): string | null;

    /**
     * @returns the left edge in map pixels
     */
    getLeft(): number;

    /**
     * @returns the top edge in map pixels
     */
    getTop(): number;

    /**
     * @returns the right edge in map pixels
     */
    getRight(): number;

    /**
     * @returns the bottom edge in map pixels
     */
    getBottom(): number;

    /**
     * @returns the center of the location's rectangle in map pixels
     */
    getCenter(): Position;

    /**
     * Sets the bounds in map pixels. Shared code only.
     */
    setBounds(left: number, top: number, right: number, bottom: number): void;

    /**
     * Moves the location so the position is its center, kept inside the map as Move Location keeps it. Shared code only.
     */
    setCenter(position: Position): void;

    /**
     * @returns true when the flag is set, which leaves the units at its elevation out of the location
     */
    hasFlag(flag: LocationFlag): boolean;

    /**
     * Sets or clears an elevation flag. Shared code only.
     */
    setFlag(flag: LocationFlag, set: boolean): void;
}

/**
 * A timer of {@link Game.every} or {@link Game.after}.
 */
interface Timer {
    /**
     * @returns the timer's id, unique in the game
     */
    getId(): number;

    /**
     * Cancels the timer. Shared code only.
     *
     * @returns false when the timer was already cancelled or has run its only call
     */
    cancel(): boolean;
}

/**
 * The options of a leaderboard.
 */
interface LeaderboardOptions {
    /**
     * The goal, which shows the goal form of the leaderboard; none shows the plain form.
     */
    goal?: number;
}

/**
 * The options of a control leaderboard.
 */
interface ControlLeaderboardOptions extends LeaderboardOptions {
    /**
     * The location, which shows Leader Board Control At Location; null or none counts everywhere.
     */
    location?: Location | null;
}

/**
 * The game: the same on every player's game. Its groups are {@link map}, {@link players}, {@link units}, {@link switches}, {@link countdown},
 * {@link leaderboard}, {@link settings}, {@link vision} and {@link combat}.
 */
interface Game {
    /**
     * @returns the number of the current game frame
     */
    getFrame(): number;

    /**
     * @returns the seconds of the trigger clock (16 frames a second) that the Elapsed Time condition compares
     */
    getElapsedTime(): number;

    /**
     * @returns true inside a decision hook and in local code, where the game state cannot change
     */
    isReadOnly(): boolean;

    /**
     * Shows a text to all players. Shared code only.
     */
    print(text: string): void;

    /**
     * @returns true while the game is paused, by {@link Game.pause}, Pause Game or the pause of the game menu
     */
    isPaused(): boolean;

    /**
     * Pauses the game as the Pause Game action does, at this frame's trigger step: nothing in a multiplayer session or while it is paused so
     * already. The paused game runs no frame, but onFrame keeps running at every trigger step with the frame number unchanged, so that it can
     * call {@link Game.resume}. Shared code only.
     */
    pause(): void;

    /**
     * Resumes the game that {@link Game.pause} or Pause Game paused, as the Unpause Game action does, at this frame's trigger step: nothing in a
     * multiplayer session. Shared code only.
     */
    resume(): void;

    /**
     * Calls a function every given number of frames, first that many frames from now ({@link Time}), at the frame start before the hooks of
     * the frame. The timers due at one frame run in the order of their due frames, then of their adding. Shared code only.
     *
     * @param frames the interval in frames, an integer of at least 1
     * @throws TypeError if the interval is not an integer or the callback not a function
     * @throws RangeError if the interval is below 1
     */
    every(frames: number, callback: () => void): Timer;

    /**
     * Calls a function once, the given number of frames from now ({@link Time}), at the frame start before the hooks of the frame. Shared code
     * only.
     *
     * @param frames the delay in frames, an integer of at least 1
     * @throws TypeError if the delay is not an integer or the callback not a function
     * @throws RangeError if the delay is below 1
     */
    after(frames: number, callback: () => void): Timer;

    /**
     * @returns the current game speed
     */
    getSpeed(): GameSpeed;

    /**
     * @returns true while the cheat's bit of the cheat flags is set
     */
    isCheatEnabled(cheat: Cheat): boolean;

    /**
     * Turns a cheat on or off and keeps it so: sets or clears its bit of the cheat flags ({@link core.game.setCheatFlags}), as an EUD write of
     * the flags does, and writes the bit again at the start of every frame, as the always-running trigger of an EUD map does. It works in a
     * network game and where the game options forbid cheats. A reveal cheat (BlackSheepWall, WarAintWhatItUsedToBe) reveals nothing at once:
     * while its bit is set the game no longer marks the tiles unseen every 100 frames, so what the players saw stays seen; in a network game
     * that tile update clears the reveal bits first and the next frame sets them again. The cheat routine of the game, which reveals the map at
     * once, is {@link core.game.applyCheats}. Shared code only.
     */
    setCheatEnabled(cheat: Cheat, enabled: boolean): void;

    /**
     * Creates a sprite that lives as long as its image scripts run, as the game creates the sprite of a spell or of dust, with its elevation and
     * its visibility from the tile below it. Shared code only.
     *
     * @returns the sprite, or null when the pools are full
     */
    createEffect(spriteType: SpriteType, position: Position, options?: EffectOptions): Sprite | null;

    /**
     * @returns the active bullets, in the order of the active list
     */
    getBullets(): Bullet[];

    /**
     * Creates a bullet of a weapon at a position for a unit, aimed at the unit's order target unit, else its order target position, as the game
     * creates one. Shared code only.
     *
     * @returns the bullet, or null when the game creates none (no free entry, no sprite, an attack the unit cannot make)
     */
    createBullet(weaponType: WeaponType, source: Unit, position: Position): Bullet | null;

    /**
     * The map: its size, tileset, terrain, locations and resource files.
     */
    readonly map: GameMap;
    /**
     * The players by slot.
     */
    readonly players: Players;
    /**
     * The units of the game: finding, counting and creating them.
     */
    readonly units: Units;
    /**
     * The switches of the trigger state.
     */
    readonly switches: Switches;
    /**
     * The countdown timer of the trigger state.
     */
    readonly countdown: Countdown;
    /**
     * The leaderboards of the leaderboard actions.
     */
    readonly leaderboard: Leaderboard;
    /**
     * The settings the game started with.
     */
    readonly settings: GameSettings;
    /**
     * The sight of the players.
     */
    readonly vision: GameVision;
    /**
     * The combat tables.
     */
    readonly combat: GameCombat;
}

/**
 * The options of {@link Game.createEffect}.
 */
interface EffectOptions {
    /**
     * The owner of the sprite, by default the player in slot 0.
     */
    owner?: Player;
    /**
     * The elevation from 0 to 255, by default 4, the elevation a new sprite starts with.
     */
    elevation?: number;
}

/**
 * A sprite of the sprite pool. The object stays with its sprite: once the sprite is gone and its slot holds another one, exists() is false and
 * every other method throws an Error. The pools of sprites and images also hold the buildings the local player remembers in the fog of war,
 * so the slots can differ from player to player in a network game: shared code may show them but should not let them decide the game.
 */
interface Sprite {
    /**
     * @returns the slot in the sprite pool ({@link CoreSprite})
     */
    getId(): number;

    /**
     * @returns true while the slot holds this sprite
     */
    exists(): boolean;

    /**
     * @returns the sprite type
     */
    getType(): SpriteType;

    /**
     * @returns the sprite's position in map pixels
     */
    getPosition(): Position;

    /**
     * @returns the owner, or null for an owner byte that is no player slot
     */
    getOwner(): Player | null;

    /**
     * @returns the elevation, 0 to 255
     */
    getElevation(): number;

    /**
     * @returns the images of the sprite, from the one drawn on top; the local selection circles and health bars are not among them
     */
    getImages(): SpriteImage[];

    /**
     * @returns the main image, or null for none
     */
    getMainImage(): SpriteImage | null;

    /**
     * @returns true when the player sees the sprite
     */
    isVisibleTo(player: Player): boolean;
}

/**
 * An image of the image pool ({@link CoreImage}). The object stays with its image: once the image is gone and its slot holds another one,
 * exists() is false and every other method throws an Error. Its frame, draw function and graphics change only what is drawn: shared code
 * changes them on every player's game, local code on this machine only.
 */
interface SpriteImage {
    /**
     * @returns the slot in the image pool
     */
    getId(): number;

    /**
     * @returns true while the slot holds this image
     */
    exists(): boolean;

    /**
     * @returns the image type
     */
    getType(): ImageType;

    /**
     * @returns the sprite the image belongs to, or null for none
     */
    getSprite(): Sprite | null;

    /**
     * @returns the drawn frame
     */
    getFrame(): number;

    /**
     * Draws a frame until the image's own frame changes, as an EUD write of the frame does.
     *
     * @param frame the frame, 0 to 65535, or null to draw the image's own frame again
     */
    setFrame(frame: number | null): void;

    /**
     * Holds a drawn frame: it stays while the image's iscript steps the image's own frame, until it is released with null, another frame is
     * set or held, or the image is gone. It changes only what is drawn; the image's own frame and its iscript go on as before. The frame is the
     * whole drawn frame, the direction included ({@link SpriteImage.getDirection}); the graphics of the image must have it.
     *
     * @param frame the frame, 0 to 65535, or null to draw the image's own frame again
     */
    holdFrame(frame: number | null): void;

    /**
     * @returns the frame offset of the image's direction inside its frame group
     */
    getDirection(): number;

    /**
     * @returns the image's draw function
     */
    getDrawFunction(): DrawFunction;

    /**
     * Draws with a draw function until the image's own draw function changes, as an EUD write of the draw function does.
     *
     * @param drawFunction the draw function, or null to draw with the image's own again
     */
    setDrawFunction(drawFunction: DrawFunction | null): void;

    /**
     * @returns the x offset from the sprite's position in pixels
     */
    getOffsetX(): number;

    /**
     * @returns the y offset from the sprite's position in pixels
     */
    getOffsetY(): number;

    /**
     * Replaces the graphics of this image while it lives; they win over those of its unit and of its image type.
     *
     * @throws TypeError if the graphics are not an object of GRP and anim resources
     */
    replaceGraphics(images: ImageGraphics): void;

    /**
     * Restores the graphics of this image that {@link SpriteImage.replaceGraphics} replaced.
     */
    restoreGraphics(): void;
}

/**
 * A bullet of the bullet table, from its creation until it is released after dying. The object stays with its bullet: once the bullet is gone
 * and its entry holds another one, exists() is false and every other method throws an Error.
 */
interface Bullet {
    /**
     * @returns the entry in the bullet table ({@link CoreBullet})
     */
    getId(): number;

    /**
     * @returns true while the entry holds this bullet
     */
    exists(): boolean;

    /**
     * @returns the weapon type that fired the bullet
     */
    getWeapon(): WeaponType;

    /**
     * @returns the bullet's flingy type
     */
    getFlingyType(): FlingyType;

    /**
     * @returns the bullet's position in map pixels
     */
    getPosition(): Position;

    /**
     * @returns the player of the bullet, or null for an owner byte that is no player slot
     */
    getPlayer(): Player | null;

    /**
     * @returns the unit that fired the bullet, or null once it is gone
     */
    getSource(): Unit | null;

    /**
     * @returns the target unit, or null for none
     */
    getTarget(): Unit | null;

    /**
     * @returns the center of the hit
     */
    getTargetPosition(): Position;

    /**
     * Sets the target unit (null for none) or the target position, as an EUD write of the bullet does: a following, bouncing or near-target
     * bullet turns to a new target unit from the next frame, a moving bullet keeps its course; the hit damages the target unit and is centered
     * on the target position. Shared code only.
     */
    setTarget(target: Unit | Position | null): void;

    /**
     * @returns the frames a moving bullet still flies, or a bullet that persists on its target position still hits
     */
    getRemainingTime(): number;

    /**
     * Sets the remaining frames, 0 to 255. Shared code only.
     *
     * @throws RangeError if the frames are outside 0 to 255
     */
    setRemainingTime(frames: number): void;

    /**
     * @returns the bounces left of a bouncing bullet
     */
    getRemainingBounces(): number;

    /**
     * @returns the bullet's state
     */
    getState(): BulletState;

    /**
     * @returns the direction, 0 to 255
     */
    getDirection(): number;

    /**
     * @returns the sprite, or null once its last image ended
     */
    getSprite(): Sprite | null;
}

/**
 * The settings the game started with ({@link Game.settings}).
 */
interface GameSettings {
    /**
     * @returns the game type of the game header
     */
    getMode(): GameMode;

    /**
     * @returns the raw game subtype of the game header
     */
    getSubtype(): number;

    /**
     * @returns true for a multiplayer game
     */
    isMultiplayer(): boolean;

    /**
     * @returns the raw victory condition of the game options
     */
    getVictoryCondition(): number;

    /**
     * @returns the raw resource setting of the game options
     */
    getResourceType(): number;

    /**
     * @returns the starting minerals of the game options
     */
    getStartingMinerals(): number;

    /**
     * @returns the raw starting units setting of the game options
     */
    getStartingUnits(): number;

    /**
     * @returns the raw tournament mode of the game options
     */
    getTournamentMode(): number;

    /**
     * @returns true when the game options permit the cheat command
     */
    areCheatsAllowed(): boolean;

    /**
     * @returns true when Brood War content is on: the expansion byte is not 0
     */
    isExpansion(): boolean;

    /**
     * Turns Brood War content on or off: sets the expansion byte to 1 or 0. Shared code only.
     */
    setExpansion(expansion: boolean): void;
}

/**
 * The options of {@link GameVision.reveal}.
 */
interface RevealOptions {
    /**
     * True to see as an air unit does, over higher ground; false by default.
     */
    air?: boolean;
}

/**
 * The sight of the players ({@link Game.vision}).
 */
interface GameVision {
    /**
     * Reveals the tiles around a position to players from 0 to 7 as a unit's sight does, until the game marks the tiles unseen again. Shared
     * code only.
     *
     * @param players    a player or players from 0 to 7
     * @param sightRange the sight range in tiles, 0 to 11
     * @throws RangeError if the sight range is outside 0 to 11
     */
    reveal(players: Player | Player[], position: Position, sightRange: number, options?: RevealOptions): void;

    /**
     * Returns the fog brightness of visible tiles: the world's fog draws visible tiles at this level, explored tiles at half of it and
     * unexplored tiles black. The minimap does not use it.
     *
     * @returns the brightness, 0 to 255, default 31
     */
    getFogBrightness(): number;

    /**
     * Sets the fog brightness of visible tiles; shared code sets it on every player's game, local code only on this machine.
     *
     * @param level the brightness, 0 to 255
     * @throws RangeError if the level is outside 0 to 255
     */
    setFogBrightness(level: number): void;
}

/**
 * The combat tables ({@link Game.combat}); the damage multipliers are on {@link DamageType}.
 */
interface GameCombat {
    /**
     * @returns the distances in pixels a fleeing unit tries, in order
     */
    getFleeDistances(): number[];

    /**
     * Sets the distances in pixels a fleeing unit tries, in order. Shared code only.
     *
     * @param distances as many distances as {@link GameCombat.getFleeDistances} returns, each at least 0
     * @throws TypeError if the array has another length
     * @throws RangeError if a distance is negative
     */
    setFleeDistances(distances: number[]): void;
}

/**
 * The game: its map, players, units, trigger state and timers.
 */
declare const game: Game;

/**
 * The map of the game ({@link Game.map}). Positions and sizes are in map pixels; tile coordinates and sizes are in tiles of 32 pixels.
 */
interface GameMap {
    /**
     * @returns the map width in pixels
     */
    getWidth(): number;

    /**
     * @returns the map height in pixels
     */
    getHeight(): number;

    /**
     * @returns the map width in tiles
     */
    getTileWidth(): number;

    /**
     * @returns the map height in tiles
     */
    getTileHeight(): number;

    /**
     * The tileset of the map and its tiles.
     */
    readonly tileset: Tileset;
    /**
     * The tiles of the map.
     */
    readonly terrain: Terrain;
    /**
     * The locations of the map.
     */
    readonly locations: Locations;
    /**
     * The files of the map's resource folder.
     */
    readonly resources: Resources;
}

/**
 * The locations of the map ({@link GameMap.locations}); one location is always the same object.
 */
interface Locations {
    /**
     * @param id the 0-based location id
     * @returns the location, or null for an id outside the map's locations
     */
    get(id: number): Location | null;

    /**
     * @param name the location name as the map stores it
     * @returns the first location with the name, or null when no location has it
     */
    findByName(name: string): Location | null;
}

/**
 * The players by slot ({@link Game.players}), from 0 (the original player 1) to 11; one player is always the same object.
 */
interface Players {
    /**
     * @param id the 0-based player slot
     * @returns the player of the slot, or null for a slot outside 0 to 11
     */
    get(id: number): Player | null;

    /**
     * @returns the players of every slot, in slot order
     */
    getAll(): Player[];

    /**
     * @returns the human players, in slot order
     */
    getHumans(): Player[];
}

/**
 * The units of the game ({@link Game.units}); one live unit is always the same object. Searches return live units only.
 */
interface Units {
    /**
     * @param id the unit's slot index ({@link Unit.getId})
     * @returns the unit in the slot, or null for an empty slot or an id outside the unit table
     */
    get(id: number): Unit | null;

    /**
     * @returns every live unit, in slot order
     */
    getAll(): Unit[];

    /**
     * @param filter the test of a unit; every live unit passes without one
     * @returns the live units that pass the filter, in slot order
     */
    find(filter?: UnitFilter): Unit[];

    /**
     * @param filter the test of a unit; every live unit passes without one
     * @returns the first live unit in slot order that passes the filter, or null for none
     */
    findOne(filter?: UnitFilter): Unit | null;

    /**
     * @param filter the test of a unit; every live unit passes without one
     * @returns the number of live units that pass the filter
     */
    count(filter?: UnitFilter): number;

    /**
     * Searches the unit finder of the game for the units whose bounds meet a rectangle. Shared code only.
     *
     * @param corner      a corner of the rectangle, in map pixels
     * @param otherCorner the opposite corner, in map pixels
     * @param filter      the test of a unit; every found unit passes without one
     * @returns the found units that pass the filter, in the finder's order
     */
    findInRectangle(corner: Position, otherCorner: Position, filter?: UnitFilter): Unit[];

    /**
     * Searches the unit finder of the game for the units whose position lies within a radius of a position. Shared code only.
     *
     * @param position the center, in map pixels
     * @param radius   the radius in pixels, at least 0; a negative radius throws a RangeError
     * @param filter   the test of a unit; every found unit passes without one
     * @returns the found units that pass the filter, in the finder's order
     */
    findInRadius(position: Position, radius: number, filter?: UnitFilter): Unit[];

    /**
     * Creates a unit as the Create Unit action does. Shared code only.
     *
     * @param type     the unit type
     * @param player   the owner
     * @param position the position in map pixels
     * @returns the new unit, or null when it could not be placed
     */
    create(type: UnitType, player: Player, position: Position): Unit | null;
}

/**
 * The switches of the trigger state ({@link Game.switches}), 0 to 255. An index outside that range throws a RangeError.
 */
interface Switches {
    /**
     * @param index the switch, 0 to 255
     * @returns true when the switch is set
     */
    isSet(index: number): boolean;

    /**
     * Sets or clears a switch as the Set Switch action does. Shared code only.
     *
     * @param index the switch, 0 to 255
     * @param set   true to set, false to clear
     */
    set(index: number, set: boolean): void;

    /**
     * Toggles a switch as the Set Switch action does. Shared code only.
     *
     * @param index the switch, 0 to 255
     */
    toggle(index: number): void;
}

/**
 * The countdown timer of the trigger state ({@link Game.countdown}), in seconds of the trigger clock (16 frames a second), kept from 0 to
 * 359999.
 */
interface Countdown {
    /**
     * @returns the countdown timer in seconds
     */
    get(): number;

    /**
     * Sets the countdown timer as the Set Countdown Timer action does. Shared code only.
     *
     * @param seconds the new value in seconds
     */
    set(seconds: number): void;

    /**
     * Adds seconds to the countdown timer. Shared code only.
     *
     * @param seconds the seconds to add
     */
    add(seconds: number): void;

    /**
     * Subtracts seconds from the countdown timer. Shared code only.
     *
     * @param seconds the seconds to subtract
     */
    subtract(seconds: number): void;

    /**
     * @returns true while the countdown timer is paused
     */
    isPaused(): boolean;

    /**
     * Pauses the countdown timer as the Pause Timer action does. Shared code only.
     */
    pause(): void;

    /**
     * Resumes the countdown timer as the Unpause Timer action does. Shared code only.
     */
    resume(): void;
}

/**
 * The leaderboards of the leaderboard actions, with a text label ({@link Game.leaderboard}).
 */
interface Leaderboard {
    /**
     * Shows the Control leaderboard of a unit type or group: the goal form with a goal option, Control At Location with a location option.
     * Shared code only.
     *
     * @param label   the label text
     * @param unit    the counted unit type or group
     * @param options the goal and the location, both optional
     */
    showControl(label: string, unit: UnitType | UnitGroup, options?: ControlLeaderboardOptions): void;

    /**
     * Shows the Resources leaderboard of a resource type; {@link ResourceType.Supply} throws a RangeError. Shared code only.
     *
     * @param label        the label text
     * @param resourceType the counted resource
     * @param options      the goal, optional
     */
    showResources(label: string, resourceType: ResourceType, options?: LeaderboardOptions): void;

    /**
     * Shows the Kills leaderboard of a unit type or group. Shared code only.
     *
     * @param label   the label text
     * @param unit    the counted unit type or group
     * @param options the goal, optional
     */
    showKills(label: string, unit: UnitType | UnitGroup, options?: LeaderboardOptions): void;

    /**
     * Shows the Points leaderboard of a score type. Shared code only.
     *
     * @param label     the label text
     * @param scoreType the counted score
     * @param options   the goal, optional
     */
    showPoints(label: string, scoreType: ScoreType, options?: LeaderboardOptions): void;

    /**
     * Shows the Greed leaderboard of the minerals and gas of every player. Shared code only.
     *
     * @param goal the resource goal
     */
    showGreed(goal: number): void;

    /**
     * @returns true when the leaderboards include computer players
     */
    areComputersIncluded(): boolean;

    /**
     * Includes or leaves out computer players, as the Leaderboard Computer Players action does. Shared code only.
     *
     * @param included true to include them
     */
    setComputersIncluded(included: boolean): void;
}

/**
 * A file of the map's resource folder, named by its path relative to resources/ with / separators. One file is always the same object.
 */
interface Resource {
    /**
     * @returns the resource handle, which the lower layer {@link core} takes
     */
    getId(): number;

    /**
     * @returns the path relative to resources/
     */
    getName(): string;
}

/**
 * An SD GRP file of the resource folder ({@link Resources.getGrp}).
 */
interface Grp extends Resource {
}

/**
 * A single-entry HD or HD2 anim file of the resource folder ({@link Resources.getAnim}).
 */
interface Anim extends Resource {
}

/**
 * A PNG or DDS picture of the resource folder to draw ({@link Drawing.drawImage}) or to show on the console.
 */
interface Image extends Resource {
    /**
     * @returns the width in pixels
     */
    getWidth(): number;

    /**
     * @returns the height in pixels
     */
    getHeight(): number;
}

/**
 * The files of the map's resource folder ({@link GameMap.resources}). A file is read once; later calls with the same name return the same
 * object. A missing file or one that is not valid for the kind throws an Error. Reading works in shared and local code.
 */
interface Resources {
    /**
     * @param name the path relative to resources/
     * @returns true when the resource folder has a file of the name
     */
    has(name: string): boolean;

    /**
     * Reads an SD GRP.
     *
     * @param name the path relative to resources/
     * @returns the GRP
     */
    getGrp(name: string): Grp;

    /**
     * Reads a single-entry HD or HD2 anim.
     *
     * @param name the path relative to resources/
     * @returns the anim
     */
    getAnim(name: string): Anim;

    /**
     * Reads a PNG or DDS picture.
     *
     * @param name the path relative to resources/
     * @returns the picture
     */
    getImage(name: string): Image;

    /**
     * Reads a file as UTF-8 text; a leading byte order mark is left out. A file that is no UTF-8 throws an Error.
     *
     * @param name the path relative to resources/
     * @returns the text
     */
    readText(name: string): string;

    /**
     * Reads a JSON file: the UTF-8 text of {@link Resources.readText}, parsed with JSON.parse. Each call returns a new value; a text that is no
     * JSON throws a SyntaxError.
     *
     * @param name the path relative to resources/
     * @returns the parsed value
     */
    readJson(name: string): any;
}

/**
 * Replacement graphics: an SD GRP, an HD anim and an HD2 anim, at least one of them. The classic graphics draw the GRP, else the installed GRP;
 * HD draws the HD anim, else the HD2 anim; HD2 draws the HD2 anim, else the HD anim at half size; without an anim HD and HD2 draw the GRP.
 */
interface ImageGraphics {
    /**
     * The GRP of the SD graphics.
     */
    sd?: Grp | null;
    /**
     * The anim of the HD graphics.
     */
    hd?: Anim | null;
    /**
     * The anim of the HD2 graphics.
     */
    hd2?: Anim | null;
}

/**
 * A tile of the map's tileset: a CV5 group and an index in it from 0 to 15. One tile is always the same object.
 */
interface Tile {
    /**
     * @returns the MTXM value, group << 4 | index, which the lower layer core.terrain takes
     */
    getId(): number;

    /**
     * @returns the CV5 group
     */
    getGroup(): number;

    /**
     * @returns the index in the group, 0 to 15
     */
    getIndex(): number;

    /**
     * @returns true when more than 12 of its 16 mini-tiles are walkable
     */
    isWalkable(): boolean;

    /**
     * @returns true when some but not all of its mini-tiles are walkable
     */
    isPartiallyWalkable(): boolean;

    /**
     * @returns true when buildings may be placed on it
     */
    isBuildable(): boolean;

    /**
     * @returns true for a creep tile of the tileset
     */
    hasCreep(): boolean;

    /**
     * @returns 0 low, 1 middle or 2 high ground
     */
    getElevation(): number;

    /**
     * @returns true for a tile that blocks the sight of ground units at every elevation
     */
    isBlockingSight(): boolean;
}

/**
 * The tileset of the map ({@link GameMap.tileset}).
 */
interface Tileset {
    /**
     * @returns the tileset id from 0 (Badlands) to 7 (Ice)
     */
    getId(): number;

    /**
     * @returns the number of CV5 tile groups
     */
    getGroupCount(): number;

    /**
     * @param group the CV5 group
     * @param index the index in the group, 0 to 15
     * @returns the tile; a RangeError for a tile the tileset does not have
     */
    getTile(group: number, index: number): Tile;
}

/**
 * The tiles of the map by tile coordinates ({@link GameMap.terrain}); a coordinate outside the map throws a RangeError. The changes apply
 * together at the next trigger step, after the frame start hooks (onFrame, the events, the timers) and before the triggers, as on a map that
 * had the tiles from the start: walkability, pathing regions, buildability, sight and the minimap follow, and ground units on tiles that became
 * unwalkable move to walkable ground nearby. Reads see the changes made so far.
 */
interface Terrain {
    /**
     * @param tileX the tile column
     * @param tileY the tile row
     * @returns the tile, for a tile with creep the one below the creep
     */
    getTile(tileX: number, tileY: number): Tile;

    /**
     * Sets a tile. Shared code only.
     *
     * @param tileX the tile column
     * @param tileY the tile row
     * @param tile  the new tile
     */
    setTile(tileX: number, tileY: number, tile: Tile): void;

    /**
     * Sets every tile a location covers, clipped to the map. Shared code only.
     *
     * @param location the covered area
     * @param tile     the new tile
     */
    fillTiles(location: Location, tile: Tile): void;

    /**
     * Copies the tiles one location covers onto the tiles another location covers. Shared code only.
     *
     * @param fromLocation the source area
     * @param toLocation   the target area; a RangeError when it does not cover as many tiles across and down as the source
     */
    copyTiles(fromLocation: Location, toLocation: Location): void;

    /**
     * The creep of the map.
     */
    readonly creep: Creep;
}

/**
 * The creep of the map by tile coordinates ({@link Terrain.creep}); a coordinate outside the map throws a RangeError. Creep these functions add
 * has no source: it stays until it is removed or recedes.
 */
interface Creep {
    /**
     * @param tileX the tile column
     * @param tileY the tile row
     * @returns true when the tile has the creep flag that the rules of the game read (placement, larvae, spells)
     */
    has(tileX: number, tileY: number): boolean;

    /**
     * Covers the footprint of a building of the unit type centered at a position with creep and grows it until no tile is added, as the game
     * does for a building that needs creep. A footprint that leaves the map throws a RangeError. Shared code only.
     *
     * @param unitType the building type whose footprint is covered
     * @param position the center in map pixels
     */
    fill(unitType: UnitType, position: Position): void;

    /**
     * Runs one spread step of a creep source of the unit type at a position; a type that spreads creep keeps to its ellipse. Shared code only.
     *
     * @param unitType the type of the creep source
     * @param position the source position in map pixels
     * @returns true when it added a tile
     */
    spread(unitType: UnitType, position: Position): boolean;

    /**
     * Removes the creep of a tile at once and gives back the terrain below it. Shared code only.
     *
     * @param tileX the tile column
     * @param tileY the tile row
     */
    remove(tileX: number, tileY: number): void;

    /**
     * Makes a creep tile recede later, at the pace of the recession, as the creep of a removed building does. Shared code only.
     *
     * @param tileX the tile column
     * @param tileY the tile row
     * @returns false for a tile that is not creep, already recedes, is occupied or that a Zerg building sustains, and when the recession is full
     */
    recede(tileX: number, tileY: number): boolean;
}

/**
 * A channel ({@link Sync.createChannel}): a value one player sends reaches the channel's handler on every player's game, the sender's
 * included, and in every replay, at the frame the game runs the command, in the order the game runs them. A value travels as the script
 * command {c: id, v: value} ({@link CoreCommand.submit}).
 */
interface Channel<T> {
    /**
     * @returns the channel's id, the number of channels created before it
     */
    getId(): number;

    /**
     * Sends a value in the local player's next outgoing turn; a replay and an observer send nothing. Shared code sends once from every human
     * player's game, so the handler runs once per such player. The handler receives a copy of the value.
     *
     * @param value null, a boolean, a number, a string, or an array or plain object of these
     * @throws TypeError if the value holds anything else
     */
    send(value: T): void;
}

/**
 * A value per player on a channel of its own ({@link Sync.createPlayerValue}): a change reaches every player's game at the frame the game runs
 * it.
 */
interface PlayerValue<T> {
    /**
     * @param player the player
     * @returns the player's value, the initial value until one was set
     */
    get(player: Player): T;

    /**
     * Sets the value of the sending player.
     *
     * @param value the new value, of the kinds {@link Channel.send} takes
     */
    set(value: T): void;

    /**
     * Sets the value of a player.
     *
     * @param player the player whose value changes
     * @param value  the new value, of the kinds {@link Channel.send} takes
     */
    set(player: Player, value: T): void;
}

/**
 * Creates channels. Every player's game must create the same channels in the same order, so they are created in shared code, best at the
 * module top level or in onStart.
 */
interface Sync {
    /**
     * Creates a channel. The handler runs as shared code with the sending player, the value, and the live units the player had selected when
     * the command ran. Shared code only. The value comes from another player's game, which may send any channel value whatever T is, so the
     * handler receives it as unknown: check its kind before using it.
     *
     * @param handler the function that receives the sent values
     * @returns the channel, whose send takes T
     * @throws TypeError if the handler is no function
     */
    createChannel<T = unknown>(handler: (player: Player, value: unknown, units: Unit[]) => void): Channel<T>;

    /**
     * Creates a value per player over a new channel. Shared code only.
     *
     * @param initial the value of every player until one is set
     * @returns the player value
     */
    createPlayerValue<T>(initial: T): PlayerValue<T>;
}

/**
 * The channels between the players' games.
 */
declare const sync: Sync;

/**
 * What only this player's machine knows, which differs from player to player. Every function works in local code only and throws a TypeError
 * in shared code.
 */
interface Local {
    /**
     * Local code only.
     *
     * @returns the local player, or null in a replay and for an observer
     */
    getPlayer(): Player | null;

    /**
     * Local code only.
     *
     * @returns true while this machine plays a replay
     */
    isReplay(): boolean;

    /**
     * Local code only.
     *
     * @returns true while the chat box is open
     */
    isChatOpen(): boolean;

    /**
     * Local code only.
     *
     * @returns the map position of the local screen's top left in pixels, or null while the client has not supplied it
     */
    getScreenPosition(): Position | null;

    /**
     * Moves the local screen's top left to a map position at once, kept inside the map; the other players' screens stay. Local code only.
     *
     * @param position the new top left in map pixels
     * @returns false while the screen is unknown
     */
    setScreenPosition(position: Position): boolean;

    /**
     * Local code only.
     *
     * @returns the map position under the pointer in pixels, or null while the client has not supplied it
     */
    getMousePosition(): Position | null;

    /**
     * Returns the pointer in interface units, the units {@link Screen} draws in, for hit tests of the script's own screen drawing: correct at any
     * interface size, graphics and camera zoom, outside the interface too. It follows every pointer move, so the input hooks read the pointer of
     * their input. Local code only.
     *
     * @returns the pointer from the interface's top left, or null while the client has not supplied it
     */
    getPointerPosition(): ScreenPoint | null;

    /**
     * Local code only.
     *
     * @returns the interface width in interface units (640, 853, ...), the width {@link Screen.getWidth} gives in onRender
     */
    getInterfaceWidth(): number;

    /**
     * Local code only.
     *
     * @returns the interface height in interface units, 480
     */
    getInterfaceHeight(): number;

    /**
     * Returns the units of the local interface's selection, which may differ from the local player's {@link Player.getSelection} until the
     * selection command runs. Local code only.
     *
     * @returns the selected units
     */
    getSelection(): Unit[];

    /**
     * Local code only.
     *
     * @returns the button set the local command card shows, or null for none
     */
    getDisplayedButtonSet(): ButtonSet | null;

    /**
     * Shows a button set on the local command card at its next refresh, as the Change Buttons action does, until the selection changes.
     * Local code only.
     *
     * @param set the button set, a unit type for its set, or a set id; anything else throws a TypeError
     */
    changeButtons(set: ButtonSet | UnitType | number): void;

    /**
     * Local code only.
     *
     * @param conditionValue the condition value of a Script button, 0 to 255
     * @returns the local state of the condition value; every state starts Hidden
     */
    getButtonState(conditionValue: number): ButtonState;

    /**
     * Sets the local state that the Script button condition gives for a condition value. Local code only.
     *
     * @param conditionValue the condition value of a Script button, 0 to 255
     * @param state          the new state
     */
    setButtonState(conditionValue: number, state: ButtonState): void;

    /**
     * Pauses the game as the game menu does: the local player's Pause command runs in a later turn on every player's game, and a network
     * pause takes one of the player's pauses left ({@link Player.getPausesLeft}). Nothing in a replay or while the game is paused. Local code
     * only.
     */
    requestPause(): void;

    /**
     * Resumes the game as the game menu does, with the local player's Resume command. Nothing in a replay or while the game runs. Local code
     * only.
     */
    requestResume(): void;
}

/**
 * The local information of this player's machine.
 */
declare const local: Local;

/**
 * A point in interface units ({@link Local.getPointerPosition}).
 */
interface ScreenPoint {
    /**
     * The column from the interface's left edge.
     */
    readonly x: number;
    /**
     * The row from the game screen's top edge.
     */
    readonly y: number;
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// Presentation: drawing in onRender, the console and the texts.
// ---------------------------------------------------------------------------------------------------------------------------------------

/**
 * A color: an unsigned 32-bit number packed 0xAARRGGBB with straight alpha.
 */
type Color = number;

/**
 * The color constants and the color constructors ({@link Color}).
 */
interface ColorStatic {
    /** Fully transparent black, 0x00000000. */
    readonly Transparent: Color;
    /** 0xFF000000. */
    readonly Black: Color;
    /** 0xFFFFFFFF. */
    readonly White: Color;
    /** 0xFF808080. */
    readonly Gray: Color;
    /** 0xFFFF0000. */
    readonly Red: Color;
    /** 0xFF00FF00. */
    readonly Green: Color;
    /** 0xFF0000FF. */
    readonly Blue: Color;
    /** 0xFFFFFF00. */
    readonly Yellow: Color;
    /** 0xFF00FFFF. */
    readonly Cyan: Color;
    /** 0xFFFF00FF. */
    readonly Magenta: Color;
    /** 0xFFFFA500. */
    readonly Orange: Color;

    /**
     * @param red   the red channel, an integer from 0 to 255
     * @param green the green channel, an integer from 0 to 255
     * @param blue  the blue channel, an integer from 0 to 255
     * @returns the opaque color; a channel outside its range throws a RangeError
     */
    fromRgb(red: number, green: number, blue: number): Color;

    /**
     * @param red   the red channel, an integer from 0 to 255
     * @param green the green channel, an integer from 0 to 255
     * @param blue  the blue channel, an integer from 0 to 255
     * @param alpha the opacity from 0 to 1, stored rounded to a byte
     * @returns the color; a value outside its range throws a RangeError
     */
    fromRgba(red: number, green: number, blue: number, alpha: number): Color;

    /**
     * @param index the palette index, 0 to 255
     * @returns the opaque color of the palette index in the map's tileset palette; an index outside 0 to 255 throws a RangeError
     */
    fromPalette(index: number): Color;
}

/**
 * The color constants and constructors.
 */
declare const Color: ColorStatic;

/**
 * A font of the game interface.
 */
interface Font extends GameType {
}

/**
 * The fonts of the game interface.
 */
interface FontTable extends ConstantTable<Font> {
    /**
     * font8.
     */
    readonly Small: Font;
    /**
     * font10, the default.
     */
    readonly Medium: Font;
    /**
     * font16.
     */
    readonly Large: Font;
    /**
     * font16x.
     */
    readonly Huge: Font;
}

/**
 * The fonts of the game interface.
 */
declare const Font: FontTable;

/**
 * How each line of a text stands from its x.
 */
interface Align extends GameType {
}

/**
 * The text alignments.
 */
interface AlignTable extends ConstantTable<Align> {
    /** The line starts at x; the default. */
    readonly Left: Align;
    /** The line is centered on x. */
    readonly Center: Align;
    /** The line ends at x. */
    readonly Right: Align;
}

/**
 * The text alignments.
 */
declare const Align: AlignTable;

/**
 * A graphics set this machine draws ({@link Screen.getGraphics}).
 */
interface GraphicsKind extends GameType {
}

/**
 * The graphics sets.
 */
interface GraphicsTable extends ConstantTable<GraphicsKind> {
    /** The classic SD graphics. */
    readonly SD: GraphicsKind;
    /** The HD graphics. */
    readonly HD: GraphicsKind;
    /** The HD2 graphics. */
    readonly HD2: GraphicsKind;
}

/**
 * The graphics sets.
 */
declare const Graphics: GraphicsTable;

/**
 * The options of an outline or a line.
 */
interface LineOptions {
    /**
     * The color, Color.White by default.
     */
    color?: Color;
    /**
     * The line thickness in the drawing's units, at least 0; 1 by default.
     */
    thickness?: number;
}

/**
 * The options of a text.
 */
interface TextOptions {
    /**
     * The font, Font.Medium by default.
     */
    font?: Font;
    /**
     * The color, Color.White by default; color codes in the text switch it.
     */
    color?: Color;
    /**
     * The alignment of each line on x, Align.Left by default.
     */
    align?: Align;
}

/**
 * A rectangle of an image in its pixels ({@link ImageOptions.source}).
 */
interface ImageRegion {
    /**
     * The left in image pixels.
     */
    x: number;
    /**
     * The top in image pixels.
     */
    y: number;
    /**
     * The width in image pixels, at least 0.
     */
    width: number;
    /**
     * The height in image pixels, at least 0.
     */
    height: number;
}

/**
 * The options of a drawn image.
 */
interface ImageOptions {
    /**
     * The drawn width in the drawing's units; by default the pixel width of the source rectangle, else of the image.
     */
    width?: number;
    /**
     * The drawn height in the drawing's units; by default the pixel height of the source rectangle, else of the image.
     */
    height?: number;
    /**
     * The opacity from 0 to 1, 1 by default; values outside are clamped.
     */
    opacity?: number;
    /**
     * The rectangle of the image to draw, as a frame of a sprite sheet or a crop; the whole image by default. A rectangle not inside the image
     * throws a RangeError.
     */
    source?: ImageRegion;
}

/**
 * Draws in one space ({@link Screen}, {@link Screen.world}, {@link Screen.minimap}). The drawing functions work only in onRender and throw a
 * TypeError anywhere else. Coordinates are numbers with fractions, drawn at the real resolution; what a call draws is drawn in call order and
 * stays until the next onRender call. A negative size or thickness throws a RangeError; a color outside 0 to 0xFFFFFFFF throws.
 */
interface Drawing {
    /**
     * Fills a rectangle. Only in onRender.
     *
     * @param x      the left
     * @param y      the top
     * @param width  the width, at least 0
     * @param height the height, at least 0
     * @param color  the color, Color.White by default
     */
    fillRectangle(x: number, y: number, width: number, height: number, color?: Color): void;

    /**
     * Draws the outline of a rectangle; the outline lies inside the rectangle. Only in onRender.
     *
     * @param x       the left
     * @param y       the top
     * @param width   the width, at least 0
     * @param height  the height, at least 0
     * @param options the color and the thickness
     */
    drawRectangle(x: number, y: number, width: number, height: number, options?: LineOptions): void;

    /**
     * Fills a circle. Only in onRender.
     *
     * @param x      the center x
     * @param y      the center y
     * @param radius the radius, at least 0
     * @param color  the color, Color.White by default
     */
    fillCircle(x: number, y: number, radius: number, color?: Color): void;

    /**
     * Draws the outline of a circle; the outline is centered on the circle. Only in onRender.
     *
     * @param x       the center x
     * @param y       the center y
     * @param radius  the radius, at least 0
     * @param options the color and the thickness
     */
    drawCircle(x: number, y: number, radius: number, options?: LineOptions): void;

    /**
     * Draws a line between two points. Only in onRender.
     *
     * @param x1      the x of the start
     * @param y1      the y of the start
     * @param x2      the x of the end
     * @param y2      the y of the end
     * @param options the color and the thickness
     */
    drawLine(x1: number, y1: number, x2: number, y2: number, options?: LineOptions): void;

    /**
     * Draws a square of one unit of line thickness with its top left at the position. Only in onRender.
     *
     * @param x     the left
     * @param y     the top
     * @param color the color, Color.White by default
     */
    drawPoint(x: number, y: number, color?: Color): void;

    /**
     * Draws a text: the lines, broken at \n, from the top at y, each aligned on x. Only in onRender.
     *
     * @param text    the text
     * @param x       the x each line is aligned on
     * @param y       the top of the first line
     * @param options the font, the color and the alignment
     */
    drawText(text: string, x: number, y: number, options?: TextOptions): void;

    /**
     * Draws an image of the resource folder. Only in onRender.
     *
     * @param image   the image
     * @param x       the left
     * @param y       the top
     * @param options the size and the opacity
     */
    drawImage(image: Image, x: number, y: number, options?: ImageOptions): void;

    /**
     * Measures a text in this machine's fonts. Local code only.
     *
     * @param text the text, its lines broken at \n
     * @param font the font, Font.Medium by default
     * @returns the width of the widest line in interface units, 0 before the client drew a frame
     */
    measureText(text: string, font?: Font): number;
}

/**
 * The game screen that onRender receives, in interface units: 480 high, as wide as the interface (640, 853, ...), drawn above the console and
 * below the game menus; sizes, line thicknesses and texts are in interface units. {@link Screen.world} draws in map pixels following the
 * camera, above the units and below the console (thicknesses and texts in map pixels, so they follow the zoom); {@link Screen.minimap} draws
 * in map pixels on the minimap (thicknesses, points and texts in interface units).
 */
interface Screen extends Drawing {
    /**
     * Local code only.
     *
     * @returns the interface width in interface units
     */
    getWidth(): number;

    /**
     * Local code only.
     *
     * @returns the interface height in interface units, 480
     */
    getHeight(): number;

    /**
     * Local code only.
     *
     * @returns the real pixels per interface unit
     */
    getScale(): number;

    /**
     * Local code only.
     *
     * @returns the graphics set this machine draws
     */
    getGraphics(): GraphicsKind;

    /**
     * Rounds an interface coordinate to the real pixel grid. Local code only.
     *
     * @param value the coordinate in interface units
     * @returns the rounded coordinate in interface units
     */
    snap(value: number): number;

    /**
     * The drawing in map pixels above the units, following the camera.
     */
    readonly world: Drawing;
    /**
     * The drawing in map pixels on the minimap.
     */
    readonly minimap: Drawing;
}

/**
 * A console dialog.
 */
interface ConsoleControl extends GameType {
}

/**
 * The console dialogs, by the names of the console parts.
 */
interface ConsoleControlTable extends ConstantTable<ConsoleControl> {
    readonly CommandCard: ConsoleControl;
    readonly Selection: ConsoleControl;
    readonly Resources: ConsoleControl;
    readonly Portrait: ConsoleControl;
    readonly Minimap: ConsoleControl;
    readonly Menu: ConsoleControl;
    readonly TextBox: ConsoleControl;
}

/**
 * The console dialogs.
 */
declare const ConsoleControl: ConsoleControlTable;

/**
 * An art file of the shown race console that an image can replace, named after the installed file. The image is stretched over the file's place
 * and size in every graphics set that draws the file. The console is hit where a frame image (Console with the SD graphics, the three pieces
 * with HD and HD2) is not transparent; a replaced frame image shows the whole frame even where EUD console transparency hides the console art.
 */
interface ConsoleImage extends GameType {
}

/**
 * The art files of the console.
 */
interface ConsoleImageTable extends ConstantTable<ConsoleImage> {
    /**
     * The SD console frame (game/tconsole.DDS and the Zerg and Protoss files).
     */
    readonly Console: ConsoleImage;
    /**
     * The left HD and HD2 console piece with the minimap frame (game/consoles/race/console_left.DDS).
     */
    readonly ConsoleLeft: ConsoleImage;
    /**
     * The center HD and HD2 console piece with the selection frame (console_center.DDS).
     */
    readonly ConsoleCenter: ConsoleImage;
    /**
     * The right HD and HD2 console piece with the portrait and command card frames (console_right.DDS).
     */
    readonly ConsoleRight: ConsoleImage;
    /**
     * The plate under the game time (console_TIME.DDS), every graphics set.
     */
    readonly ConsoleTime: ConsoleImage;
    /**
     * The plate under the APM number (console_APM.DDS), every graphics set.
     */
    readonly ConsoleApm: ConsoleImage;
    /**
     * The overlay over the portrait (conover.DDS), every graphics set.
     */
    readonly Conover: ConsoleImage;
}

/**
 * The art files of the console.
 */
declare const ConsoleImage: ConsoleImageTable;

/**
 * The interface edge that the classic 640 by 480 screen of a console dialog's positions stands at ({@link ConsoleControlHandle.setAnchor}).
 */
interface ConsoleAnchor extends GameType {
}

/**
 * The interface edges of the console dialogs.
 */
interface ConsoleAnchorTable extends ConstantTable<ConsoleAnchor> {
    /** The classic screen stands at the interface's left edge. */
    readonly Left: ConsoleAnchor;
    /** The classic screen stands centered in the interface, the installed place. */
    readonly Center: ConsoleAnchor;
    /** The classic screen stands at the interface's right edge. */
    readonly Right: ConsoleAnchor;
}

/**
 * The interface edges of the console dialogs.
 */
declare const ConsoleAnchor: ConsoleAnchorTable;

/**
 * The identifiers of the well-known controls of the console dialogs in their definitions, for {@link ConsoleControlHandle.setChildVisible},
 * {@link ConsoleControlHandle.setChildPosition}, {@link ConsoleControlHandle.restoreChild} and {@link ConsoleControlHandle.getChildRectangle} of
 * the dialog the name starts with. A numbered series counts from 1.
 */
interface ConsoleChildTable {
    /** 1 to 9: the command card buttons, row by row from the top left. */
    readonly CommandCardButton1: number;
    readonly CommandCardButton2: number;
    readonly CommandCardButton3: number;
    readonly CommandCardButton4: number;
    readonly CommandCardButton5: number;
    readonly CommandCardButton6: number;
    readonly CommandCardButton7: number;
    readonly CommandCardButton8: number;
    readonly CommandCardButton9: number;
    /** 1: the wireframe of a single selected unit. */
    readonly SelectionWireframe: number;
    /** -5: the unit name line. */
    readonly SelectionName: number;
    /** -7: the hit points line under the wireframe. */
    readonly SelectionHitPoints: number;
    /** -8: the energy or time line under the wireframe. */
    readonly SelectionEnergy: number;
    /** 2 to 6: the production queue, the current slot first, then the waiting slots from the left. */
    readonly SelectionQueue1: number;
    readonly SelectionQueue2: number;
    readonly SelectionQueue3: number;
    readonly SelectionQueue4: number;
    readonly SelectionQueue5: number;
    /** 7: the progress bar of the production queue. */
    readonly SelectionProgress: number;
    /** 33 to 44: the wireframes of a group selection, column by column from the top left. */
    readonly SelectionGroup1: number;
    readonly SelectionGroup2: number;
    readonly SelectionGroup3: number;
    readonly SelectionGroup4: number;
    readonly SelectionGroup5: number;
    readonly SelectionGroup6: number;
    readonly SelectionGroup7: number;
    readonly SelectionGroup8: number;
    readonly SelectionGroup9: number;
    readonly SelectionGroup10: number;
    readonly SelectionGroup11: number;
    readonly SelectionGroup12: number;
    /** 1 to 6: the resource counters from the right: the supply of each race the player has supply of, then gas, then minerals. */
    readonly ResourcesCounter1: number;
    readonly ResourcesCounter2: number;
    readonly ResourcesCounter3: number;
    readonly ResourcesCounter4: number;
    readonly ResourcesCounter5: number;
    readonly ResourcesCounter6: number;
    /** -10: the countdown timer. */
    readonly ResourcesCountdown: number;
    /** 1: the portrait. */
    readonly PortraitPicture: number;
    /** 1: the menu button. */
    readonly MenuButton: number;
    /** 1: the minimap area ({@link Console.getMinimapRectangle}). */
    readonly MinimapArea: number;
    /** 2: the terrain button. */
    readonly MinimapTerrain: number;
    /** 3: the message button of a multiplayer game. */
    readonly MinimapMessage: number;
    /** 4: the diplomacy button of a multiplayer game, at the place of the terrain button. */
    readonly MinimapDiplomacy: number;
    /** 7: the chat line of the text box. */
    readonly TextBoxEdit: number;
}

/**
 * The identifiers of the console dialogs' controls.
 */
declare const ConsoleChild: ConsoleChildTable;

/**
 * A rectangle in interface units ({@link ConsoleControlHandle.getRectangle}).
 */
interface ScreenRectangle {
    /**
     * The left from the interface's left edge.
     */
    readonly x: number;
    /**
     * The top from the game screen's top edge.
     */
    readonly y: number;
    /**
     * The width.
     */
    readonly width: number;
    /**
     * The height.
     */
    readonly height: number;
}

/**
 * One console dialog ({@link Console.getControl}), placed by its top left in the classic 640 by 480 screen, which stands centered in a wider
 * interface or at its anchor's edge. A setting wins over EUD memory.
 */
interface ConsoleControlHandle {
    /**
     * @returns the dialog
     */
    getControl(): ConsoleControl;

    /**
     * Places the dialog by its top left.
     *
     * @param x the left in classic screen pixels, rounded
     * @param y the top in classic screen pixels, rounded
     */
    setPosition(x: number, y: number): void;

    /**
     * Shows or hides the dialog.
     *
     * @param visible true to show it
     */
    setVisible(visible: boolean): void;

    /**
     * @returns false while the script hides the dialog
     */
    isVisible(): boolean;

    /**
     * Shows or hides a control of the dialog; it wins over EUD memory until {@link ConsoleControlHandle.restoreChild}.
     *
     * @param id      the control's identifier in the dialog's definition, -32768 to 32767 ({@link ConsoleChild} names them)
     * @param visible true to show it
     */
    setChildVisible(id: number, visible: boolean): void;

    /**
     * Places a control of the dialog by its top left; it wins over EUD memory until {@link ConsoleControlHandle.restoreChild}.
     *
     * @param id the control's identifier in the dialog's definition, -32768 to 32767 ({@link ConsoleChild} names them)
     * @param x  the left in classic screen pixels, rounded
     * @param y  the top in classic screen pixels, rounded
     */
    setChildPosition(id: number, x: number, y: number): void;

    /**
     * Forgets the visibility and the position set on a control of the dialog, so that EUD memory drives it again.
     *
     * @param id the control's identifier in the dialog's definition, -32768 to 32767 ({@link ConsoleChild} names them)
     */
    restoreChild(id: number): void;

    /**
     * Sets the interface edge that the classic screen of the dialog's positions, and those of its controls, stands at: with Right, a position
     * set for a 640 wide screen stays at the same distance from the right edge at any interface width. Positions EUD memory gives follow it too;
     * the console art and its transparency stay centered.
     *
     * @param anchor the edge, ConsoleAnchor.Center by default
     */
    setAnchor(anchor: ConsoleAnchor): void;

    /**
     * Returns the rectangle of the dialog as the client last drew it, placed by the settings and EUD memory, for hit tests and layouts of the
     * script's own drawing. The minimap dialog grows with the minimap size ({@link Console.setMinimapSize}). Local code only.
     *
     * @returns the rectangle in interface units, or null while the dialog is hidden or the console was not drawn
     */
    getRectangle(): ScreenRectangle | null;

    /**
     * Returns the rectangle of a control of the dialog as the client last drew it, such as a command card button or the minimap area (the
     * minimap size of {@link Console.setMinimapSize}). Local code only.
     *
     * @param id the control's identifier in the dialog's definition, -32768 to 32767 ({@link ConsoleChild} names them)
     * @returns the rectangle in interface units, or null while the control or its dialog is hidden, the dialog has no such control or the
     * console was not drawn
     */
    getChildRectangle(id: number): ScreenRectangle | null;
}

/**
 * The console ({@link Display.console}). Shared code sets it on every player's game, local code only on this machine; it changes no game
 * state. A setting wins over what EUD memory gives for the same item (the console, a dialog's position, a dialog's visibility, a console art
 * file, a resource icon); EUD memory still drives the items the script never set.
 */
interface Console {
    /**
     * Shows or hides the whole console.
     *
     * @param visible true to show it
     */
    setVisible(visible: boolean): void;

    /**
     * @returns false while the script hides the console
     */
    isVisible(): boolean;

    /**
     * @param control the dialog
     * @returns the object of the dialog, the same for the same dialog
     */
    getControl(control: ConsoleControl): ConsoleControlHandle;

    /**
     * Draws an image instead of a console art file of the shown race console.
     *
     * @param consoleImage the art file
     * @param image        the image, or null for the installed file
     */
    replaceImage(consoleImage: ConsoleImage, image: Image | null): void;

    /**
     * Sets the side of the drawn minimap area: the minimap, its view frame and the drawing of {@link Screen.minimap} grow or shrink with it, and
     * clicks hit the drawn minimap. The minimap dialog grows or shrinks with the area, 10 + size wide and 37 + size high, its controls kept at
     * their offsets from its top left (the area at 6, 33): a dialog placed by {@link ConsoleControlHandle.setPosition} keeps its top left, an
     * unplaced one its bottom left, so that the area keeps its bottom. Where EUD console transparency hides the console, it hides the minimap too.
     *
     * @param size the side in interface units, 1 to 480; 128 is the installed size; another value throws a RangeError
     */
    setMinimapSize(size: number): void;

    /**
     * @returns the side of the drawn minimap area in interface units
     */
    getMinimapSize(): number;

    /**
     * Returns the rectangle of the minimap area as the client last drew it, the minimap size wide and high. Local code only.
     *
     * @returns the rectangle in interface units, or null while the area or the minimap dialog is hidden or the console was not drawn
     */
    getMinimapRectangle(): ScreenRectangle | null;

    /**
     * Draws an image for the console icon of a resource, every race's icon, fitted into the icon's box.
     *
     * @param resourceType Minerals, Gas or Supply; MineralsAndGas throws a TypeError
     * @param image        the image, or null for the icon of EUD memory or the installed one
     */
    setResourceIcon(resourceType: ResourceType, image: Image | null): void;
}

/**
 * The stat_txt strings ({@link Display.strings}). Shared code sets them on every player's game, local code only on this machine.
 */
interface Strings {
    /**
     * Shows a text instead of a stat_txt string. It wins over the installed table and the table EUD memory commits.
     *
     * @param id   the one-based string id, 1 to 65535
     * @param text the text, or null to show the game's string again
     */
    setStatText(id: number, text: string | null): void;
}

/**
 * The message lines on the screen ({@link Display.messages}), 0 to 12: lines 0 to 10 take the messages in turn from the cursor, line 12 the
 * status texts. A written line keeps the expiry time of the message it held, as an EUD write does, so a line whose message expired is cleared at
 * the next drawn frame. A line outside 0 to 12 throws a RangeError. Shared code changes the lines on every player's game, local code only on
 * this machine.
 */
interface Messages {
    /**
     * Empties every message line on the screen.
     */
    clear(): void;

    /**
     * @param line the line, 0 to 12
     * @returns the text of the line
     */
    getLine(line: number): string;

    /**
     * Stores a text in a line.
     *
     * @param line the line, 0 to 12
     * @param text the text; its UTF-8 bytes past the first 217 are cut
     */
    setLine(line: number, text: string): void;

    /**
     * @returns the line the next message starts at, a byte; 11 or more starts at line 0
     */
    getCursor(): number;

    /**
     * Sets the line the next message starts at.
     *
     * @param line the line, 0 to 255; 11 or more starts at line 0
     */
    setCursor(line: number): void;
}

/**
 * The game sounds ({@link Display.sound}). Shared code plays them on every player's game, local code only on this machine.
 */
interface DisplaySound {
    /**
     * Plays a sound at a position, louder the nearer it is to the screen, whether the players see the position or not. A sound of local code
     * plays with the sounds of the next frame start.
     *
     * @param soundId  the sfxdata.dat id, 0 to 1143
     * @param position the position in map pixels
     */
    playAt(soundId: number, position: Position): void;
}

/**
 * The graphics of the game's images ({@link Display.images}). Shared code replaces them on every player's game, local code only on this
 * machine.
 */
interface DisplayImages {
    /**
     * Replaces the graphics of every image of an image type.
     *
     * @param imageType the image type
     * @param images    the new graphics; a value that is no object throws a TypeError
     */
    replace(imageType: ImageType, images: ImageGraphics): void;

    /**
     * Restores the installed graphics of an image type.
     *
     * @param imageType the image type
     */
    restore(imageType: ImageType): void;

    /**
     * Replaces the graphics of a unit's images of an image type while the unit lives. A gone unit throws an Error.
     *
     * @param unit      the unit
     * @param images    the new graphics
     * @param imageType the image type, by default that of the unit's main image now
     */
    replaceUnit(unit: Unit, images: ImageGraphics, imageType?: ImageType): void;

    /**
     * Restores the graphics of a unit's images of an image type. A gone unit throws an Error.
     *
     * @param unit      the unit
     * @param imageType the image type, by default that of the unit's main image now
     */
    restoreUnit(unit: Unit, imageType?: ImageType): void;
}

/**
 * The presentation that stays until it is changed: the console, the texts, the messages, the sounds, the image graphics and the command
 * card. Shared code changes it on every player's game, local code only on this machine; it changes no game state. Drawing is the
 * {@link Screen} of {@link Hooks.onRender}.
 */
interface Display {
    /**
     * The console and its dialogs.
     */
    readonly console: Console;
    /**
     * The stat_txt strings.
     */
    readonly strings: Strings;
    /**
     * The message lines on the screen.
     */
    readonly messages: Messages;
    /**
     * The game sounds.
     */
    readonly sound: DisplaySound;
    /**
     * The graphics of the game's images.
     */
    readonly images: DisplayImages;
    /**
     * The command card's button sets and the script buttons.
     */
    readonly commandCard: CommandCard;
}

/**
 * The presentation of the game.
 */
declare const display: Display;

/**
 * The options of a button inserted into a button set ({@link ButtonSet.insertButton}).
 */
interface ButtonOptions {
    /**
     * The position on the card, 1 to 9 (left to right, top to bottom).
     */
    position: number;
    /**
     * The cmdicons frame, 0 to 65535; 0 by default.
     */
    icon?: number;
    /**
     * The condition that decides whether the button is shown, enabled or disabled.
     */
    condition: ButtonCondition;
    /**
     * The condition's value, 0 to 255; 0 by default.
     */
    conditionValue?: number;
    /**
     * The action a press runs.
     */
    action: ButtonAction;
    /**
     * The action's value, 0 to 65535; 0 by default.
     */
    actionValue?: number;
    /**
     * The text shown instead of the enabled string; it may hold line breaks and the text color control characters.
     */
    text?: string;
    /**
     * The hotkey of the text, one character; none by default.
     */
    hotkey?: string;
    /**
     * The kind of the text, ButtonKind.None by default.
     */
    kind?: ButtonKind;
    /**
     * The text shown instead of the disabled string.
     */
    disabledText?: string;
    /**
     * The one-based stat_txt id of the enabled string, 0 to 65535; 0 (none) by default.
     */
    enabledString?: number;
    /**
     * The one-based stat_txt id of the disabled string, 0 to 65535; 0 (none) by default.
     */
    disabledString?: number;
}

/**
 * The target of a script target button that onPressed receives.
 */
interface ButtonTarget {
    /**
     * The targeted map position in pixels.
     */
    readonly position: Position;
    /**
     * The targeted unit, or null for the ground; a unit that is gone since has exists() false.
     */
    readonly unit: Unit | null;
}

/**
 * The options of a script button ({@link CommandCard.createButton}).
 */
interface ScriptButtonOptions {
    /**
     * The unit type whose button set gets the button; buttonSet wins when both are given.
     */
    unitType?: UnitType;
    /**
     * The button set that gets the button, or its id.
     */
    buttonSet?: ButtonSet | number;
    /**
     * The position on the card, 1 to 9 (left to right, top to bottom).
     */
    position: number;
    /**
     * The cmdicons frame, 0 to 65535.
     */
    icon: number;
    /**
     * The text of the enabled button; it may hold line breaks and the text color control characters.
     */
    text?: string;
    /**
     * The hotkey of the text, one character.
     */
    hotkey?: string;
    /**
     * The kind of the text, ButtonKind.None by default.
     */
    kind?: ButtonKind;
    /**
     * The text of the disabled (gray) button.
     */
    disabledText?: string;
    /**
     * True for a button whose press enters a targeting mode; the target, a unit or the ground, goes to onPressed.
     */
    target?: boolean;

    /**
     * Tells for a unit whether the button acts for it. Each local frame the button is enabled when it returns true for a unit of the local
     * selection, else disabled when it returns false for one, else hidden; and when a press runs, every player's game calls it again for
     * each unit the sender had selected and passes only those for which it returned true. It must read the game state only, never local
     * information. Without it the button is enabled and every selected unit passes.
     *
     * @param unit the unit
     * @returns true enabled, false disabled (gray), null hidden
     */
    isEnabled?(unit: Unit): boolean | null;

    /**
     * Called as local code on the pressing machine for the press of the button and, for a target button, for its target too; the record kind
     * tells them apart (InputKind.ButtonPressed, InputKind.ButtonTargeted). Consuming the press sends nothing, and a target button then does
     * not start its targeting mode; consuming the target sends nothing and keeps the targeting mode.
     *
     * @param event the press, or the target of a target button
     */
    onLocalPressed?(event: LocalButtonPressedEvent | LocalButtonTargetedEvent): void;

    /**
     * Called as shared code on every player's game and in every replay when the press runs.
     *
     * @param player the player who pressed the button
     * @param units  the units the player had selected that isEnabled passed
     * @param target the target, or null for a button without target
     */
    onPressed?(player: Player, units: Unit[], target: ButtonTarget | null): void;
}

/**
 * A script button ({@link CommandCard.createButton}): its id is its condition value and action value.
 */
interface ScriptButton {
    /**
     * @returns the id, 0 to 255, in the order the buttons were created
     */
    getId(): number;

    /**
     * @returns the button set that holds the button
     */
    getButtonSet(): ButtonSet;

    /**
     * @returns the button in its set's table, or null once it was removed
     */
    getButton(): Button | null;
}

/**
 * The command card ({@link Display.commandCard}).
 */
interface CommandCard {
    /**
     * @param set a unit type for its set, or a set id
     * @returns the button set, or null for an id outside the table
     */
    getButtonSet(set: UnitType | number): ButtonSet | null;

    /**
     * Creates a script button, at most 256 per game; one more throws a RangeError. The button goes after the set's buttons up to its
     * position, with the condition Script and the action Script, or ScriptTarget for a target button. Shared code only.
     *
     * @param options the set, the look and the callbacks of the button
     * @returns the script button
     */
    createButton(options: ScriptButtonOptions): ScriptButton;
}

/**
 * A button set of the command card's table, 0 to 249 (a unit type's set has the unit type's id); one set is always the same object. Its buttons
 * are in the order the card tries them for each position. Shared code changes the table on every player's game, local code only on this
 * machine; it changes no game state. Sets may share one array of buttons, and a change changes every set that shares it. An EUD write of the
 * table replaces the changes. A button index outside the set throws a RangeError.
 */
interface ButtonSet {
    /**
     * @returns the set id, 0 to 249
     */
    getId(): number;

    /**
     * @returns the number of buttons in the set's array
     */
    getButtonCount(): number;

    /**
     * @returns the buttons in table order
     */
    getButtons(): Button[];

    /**
     * @param index the 0-based index in the table
     * @returns the button, or null outside the set
     */
    getButton(index: number): Button | null;

    /**
     * Inserts a button.
     *
     * @param index   the 0-based index it is inserted before, or the button count for the end
     * @param options the button
     * @returns the inserted button
     */
    insertButton(index: number, options: ButtonOptions): Button;

    /**
     * Removes a button; the buttons after it move up.
     *
     * @param index the 0-based index of the button
     */
    removeButton(index: number): void;

    /**
     * Takes the array of another set, so that a change of either changes both.
     *
     * @param other the set, a unit type for its set, or a set id
     */
    share(other: ButtonSet | UnitType | number): void;

    /**
     * Takes an own copy of the array.
     */
    unshare(): void;

    /**
     * @returns the first set that holds this set's array, the same for the sets that share it
     */
    getArray(): ButtonSet;

    /**
     * Takes the installed array and connected set again.
     */
    restore(): void;

    /**
     * @returns the connected set, or null for none
     */
    getConnectedSet(): ButtonSet | null;

    /**
     * Sets the connected set.
     *
     * @param set the set, a unit type for its set, a set id, or null for none
     */
    setConnectedSet(set: ButtonSet | UnitType | number | null): void;
}

/**
 * A button of a set by its index: after a button before it is inserted or removed, the object names another button. Once the index is outside
 * the set, every method but getButtonSet and getIndex throws a RangeError. Shared code changes the button on every player's game, local code
 * only on this machine.
 */
interface Button {
    /**
     * @returns the set whose table holds the button
     */
    getButtonSet(): ButtonSet;

    /**
     * @returns the 0-based index in the set's table
     */
    getIndex(): number;

    /**
     * @returns the position on the card, 1 to 9
     */
    getPosition(): number;

    /**
     * @param position the position on the card, 1 to 9
     */
    setPosition(position: number): void;

    /**
     * @returns the cmdicons frame
     */
    getIcon(): number;

    /**
     * @param icon the cmdicons frame, 0 to 65535
     */
    setIcon(icon: number): void;

    /**
     * @returns the condition, or null for an unknown function
     */
    getCondition(): ButtonCondition | null;

    /**
     * @param condition the condition
     */
    setCondition(condition: ButtonCondition): void;

    /**
     * @returns the condition's value, 0 to 255
     */
    getConditionValue(): number;

    /**
     * @param value the condition's value, 0 to 255
     */
    setConditionValue(value: number): void;

    /**
     * @returns the action, or null for an unknown function
     */
    getAction(): ButtonAction | null;

    /**
     * @param action the action
     */
    setAction(action: ButtonAction): void;

    /**
     * @returns the action's value, 0 to 65535
     */
    getActionValue(): number;

    /**
     * @param value the action's value, 0 to 65535
     */
    setActionValue(value: number): void;

    /**
     * @returns the one-based stat_txt id of the enabled string, 0 for none
     */
    getEnabledString(): number;

    /**
     * @returns the one-based stat_txt id of the disabled string, 0 for none
     */
    getDisabledString(): number;

    /**
     * @returns the text shown instead of the enabled string, without hotkey and kind; null while the button shows its string
     */
    getText(): string | null;

    /**
     * @returns the hotkey character of the text, or null without a text or a hotkey
     */
    getHotkey(): string | null;

    /**
     * @returns the kind of the text, or null without a text
     */
    getKind(): ButtonKind | null;

    /**
     * Sets the text shown instead of the enabled string.
     *
     * @param text    the text, or null to show the enabled string again
     * @param options the hotkey (one character, null for none) and the kind; a left-out option keeps the current one
     * @throws TypeError if the hotkey is neither one character nor null
     */
    setText(text: string | null, options?: { hotkey?: string | null; kind?: ButtonKind }): void;

    /**
     * @returns the text shown instead of the disabled string, or null while the button shows its string
     */
    getDisabledText(): string | null;

    /**
     * Sets the text a disabled button shows.
     *
     * @param text the text, or null to show the disabled string again
     */
    setDisabledText(text: string | null): void;
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// The events and decisions of the hooks.
// ---------------------------------------------------------------------------------------------------------------------------------------

/**
 * An event of a unit, with the values of the moment it happened. A unit of an event may be gone since ({@link Unit.exists}).
 */
interface UnitEvent {
    /**
     * @returns the event's record of {@link CoreEvents.getFrameEvents}
     */
    getRecord(): CoreEventRecord;

    /**
     * @returns the frame the event happened in
     */
    getFrame(): number;

    /**
     * @returns the unit
     */
    getUnit(): Unit;

    /**
     * @returns the unit's type then
     */
    getUnitType(): UnitType;

    /**
     * @returns the unit's owner then
     */
    getOwner(): Player;
}

/**
 * A unit that was created ({@link Hooks.onUnitCreated}).
 */
interface UnitCreatedEvent extends UnitEvent {
}

/**
 * A unit whose construction, training or morph completed ({@link Hooks.onUnitCompleted}).
 */
interface UnitCompletedEvent extends UnitEvent {
}

/**
 * A unit that started to die ({@link Hooks.onUnitDied}).
 */
interface UnitDiedEvent extends UnitEvent {
    /**
     * @returns the unit that killed it, or null when unknown
     */
    getKiller(): Unit | null;

    /**
     * @returns the player of the killer, or null when unknown
     */
    getKillerPlayer(): Player | null;

    /**
     * @returns the weapon that killed it, or null when unknown
     */
    getWeapon(): WeaponType | null;
}

/**
 * Hit point damage to a unit ({@link Hooks.onUnitDamaged}).
 */
interface UnitDamagedEvent extends UnitEvent {
    /**
     * @returns the attacking unit, or null when unknown
     */
    getSource(): Unit | null;

    /**
     * @returns the player of the attack, or null when unknown
     */
    getSourcePlayer(): Player | null;

    /**
     * @returns the weapon, or null when unknown
     */
    getWeapon(): WeaponType | null;

    /**
     * @returns the damage in shown hit points, with fractions
     */
    getAmount(): number;
}

/**
 * An order issued to a unit ({@link Hooks.onOrderIssued}).
 */
interface OrderIssuedEvent extends UnitEvent {
    /**
     * @returns the order
     */
    getOrder(): Order;

    /**
     * @returns the target unit, or null for none
     */
    getTarget(): Unit | null;

    /**
     * @returns the target position in map pixels
     */
    getTargetPosition(): Position;

    /**
     * @returns the unit type the order builds or trains, or null for none
     */
    getBuildType(): UnitType | null;

    /**
     * @returns true for an order queued with Shift
     */
    isQueued(): boolean;
}

/**
 * A target that an automatic target search found ({@link Hooks.onTargetAcquired}).
 */
interface TargetAcquiredEvent extends UnitEvent {
    /**
     * @returns the found target, or null for none
     */
    getTarget(): Unit | null;
}

/**
 * A unit that a producer trained ({@link Hooks.onUnitTrained}).
 */
interface UnitTrainedEvent extends UnitEvent {
    /**
     * @returns the producing unit, or null when unknown
     */
    getProducer(): Unit | null;
}

/**
 * A tech that a unit finished researching ({@link Hooks.onResearchCompleted}).
 */
interface ResearchCompletedEvent extends UnitEvent {
    /**
     * @returns the researched tech
     */
    getTech(): TechType;
}

/**
 * An upgrade level that a unit finished ({@link Hooks.onUpgradeCompleted}).
 */
interface UpgradeCompletedEvent extends UnitEvent {
    /**
     * @returns the upgrade
     */
    getUpgrade(): UpgradeType;

    /**
     * @returns the reached level
     */
    getLevel(): number;
}

/**
 * A spell that a unit cast ({@link Hooks.onSpellCast}).
 */
interface SpellCastEvent extends UnitEvent {
    /**
     * @returns the tech of the spell
     */
    getTech(): TechType;

    /**
     * @returns the target unit, or null for none
     */
    getTarget(): Unit | null;

    /**
     * @returns the target position in map pixels
     */
    getTargetPosition(): Position;
}

/**
 * A unit that changed its owner ({@link Hooks.onOwnerChanged}); {@link UnitEvent.getOwner} is the new owner.
 */
interface OwnerChangedEvent extends UnitEvent {
    /**
     * @returns the owner before the change
     */
    getPreviousOwner(): Player;
}

/**
 * A unit that entered a transport or a Bunker ({@link Hooks.onUnitLoaded}).
 */
interface UnitLoadedEvent extends UnitEvent {
    /**
     * @returns the transport or Bunker, or null when unknown
     */
    getTransport(): Unit | null;
}

/**
 * A unit that left a transport or a Bunker ({@link Hooks.onUnitUnloaded}).
 */
interface UnitUnloadedEvent extends UnitEvent {
    /**
     * @returns the transport or Bunker, or null when unknown
     */
    getTransport(): Unit | null;
}

/**
 * An event of a bullet, with the values of the moment it happened.
 */
interface BulletEvent {
    /**
     * @returns the event's record of {@link CoreEvents.getFrameEvents}
     */
    getRecord(): CoreEventRecord;

    /**
     * @returns the frame the event happened in
     */
    getFrame(): number;

    /**
     * @returns the bullet; once it is gone its exists() is false
     */
    getBullet(): Bullet;

    /**
     * @returns the bullet's weapon
     */
    getWeapon(): WeaponType;

    /**
     * @returns the player of the bullet, or null for none
     */
    getPlayer(): Player | null;

    /**
     * @returns the unit that fired it, or null for none
     */
    getSource(): Unit | null;

    /**
     * @returns the target unit, or null for none
     */
    getTarget(): Unit | null;

    /**
     * @returns the target position in map pixels
     */
    getTargetPosition(): Position;
}

/**
 * A bullet that was created ({@link Hooks.onBulletCreated}).
 */
interface BulletCreatedEvent extends BulletEvent {
}

/**
 * A bullet that hit or missed ({@link Hooks.onBulletHit}).
 */
interface BulletHitEvent extends BulletEvent {
    /**
     * @returns true when the bullet missed its target
     */
    isMissed(): boolean;
}

/**
 * A player who left the game or was dropped ({@link Hooks.onPlayerLeft}).
 */
interface PlayerLeftEvent {
    /**
     * @returns the event's record of {@link CoreEvents.getFrameEvents}
     */
    getRecord(): CoreEventRecord;

    /**
     * @returns the frame the event happened in
     */
    getFrame(): number;

    /**
     * @returns the player who left
     */
    getPlayer(): Player;

    /**
     * @returns the signed departure reason of the player's Leave Game command
     */
    getReason(): number;
}

/**
 * The result a player received ({@link Hooks.onPlayerResult}): the triggers ended the player's game, or the player left.
 */
interface PlayerResultEvent {
    /**
     * @returns the event's record of {@link CoreEvents.getFrameEvents}
     */
    getRecord(): CoreEventRecord;

    /**
     * @returns the frame the event happened in
     */
    getFrame(): number;

    /**
     * @returns the player who received the result
     */
    getPlayer(): Player;

    /**
     * @returns the raw result: 1 dropped, 2 defeat, 3 victory, 4 secured victory, 5 draw
     */
    getResult(): number;

    /**
     * @returns true for a victory, secured or not
     */
    isVictory(): boolean;

    /**
     * @returns true for a defeat
     */
    isDefeat(): boolean;

    /**
     * @returns true for a draw
     */
    isDraw(): boolean;

    /**
     * @returns true for a dropped player
     */
    isDropped(): boolean;
}

/**
 * An input of the local player that a local input hook receives, seen on this machine only. Consuming it keeps it from the game.
 */
interface LocalEvent {
    /**
     * @returns the input's record of {@link CoreLocal.getInputEvents}, whose consumed field {@link LocalEvent.consume} sets
     */
    getRecord(): CoreInputRecord;

    /**
     * Keeps a pressed key or mouse button, a chat line or a command card press from the game's own handling; it has no effect on a release.
     */
    consume(): void;

    /**
     * @returns true once a hook consumed the input
     */
    isConsumed(): boolean;
}

/**
 * A key or mouse button input of the local player.
 */
interface LocalInputEvent extends LocalEvent {
    /**
     * @returns the modifier mask ({@link Modifier})
     */
    getModifiers(): number;

    /**
     * @returns the map position under the pointer in pixels, or null while unknown
     */
    getMousePosition(): Position | null;

    /**
     * @returns true while Shift was held
     */
    isShift(): boolean;

    /**
     * @returns true while Control was held
     */
    isControl(): boolean;

    /**
     * @returns true while Alt was held
     */
    isAlt(): boolean;
}

/**
 * A key the local player pressed or released ({@link Hooks.onLocalKeyDown}, {@link Hooks.onLocalKeyUp}).
 */
interface LocalKeyEvent extends LocalInputEvent {
    /**
     * @returns the key
     */
    getKey(): Key;
}

/**
 * A mouse button the local player pressed or released ({@link Hooks.onLocalMouseDown}, {@link Hooks.onLocalMouseUp}).
 */
interface LocalMouseButtonEvent extends LocalInputEvent {
    /**
     * @returns the mouse button
     */
    getButton(): MouseButton;
}

/**
 * A chat line of the local player ({@link Hooks.onLocalChat}); a consumed line is neither sent nor shown.
 */
interface LocalChatEvent extends LocalEvent {
    /**
     * @returns the chat line
     */
    getText(): string;
}

/**
 * A command card button the local player pressed ({@link Hooks.onLocalButtonPressed}). A consumed press runs no action.
 */
interface LocalButtonPressedEvent extends LocalEvent {
    /**
     * @returns the shown button set
     */
    getButtonSet(): ButtonSet;

    /**
     * @returns the button of the set's table, or null for a button of no table entry
     */
    getButton(): Button | null;

    /**
     * @returns the position on the card, 1 to 9
     */
    getPosition(): number;

    /**
     * @returns the condition, or null for an unknown function
     */
    getCondition(): ButtonCondition | null;

    /**
     * @returns the condition's value
     */
    getConditionValue(): number;

    /**
     * @returns the action, or null for an unknown function
     */
    getAction(): ButtonAction | null;

    /**
     * @returns the action's value
     */
    getActionValue(): number;

    /**
     * @returns true while Shift was held
     */
    isQueued(): boolean;

    /**
     * @returns true for a hotkey press, false for a click
     */
    isHotkey(): boolean;
}

/**
 * The target of a ScriptTarget button ({@link Hooks.onLocalButtonTargeted}). A consumed target keeps the targeting mode.
 */
interface LocalButtonTargetedEvent extends LocalButtonPressedEvent {
    /**
     * @returns the targeted unit, or null for the ground or a unit that is gone
     */
    getTarget(): Unit | null;

    /**
     * @returns the targeted map position in pixels
     */
    getTargetPosition(): Position;
}

/**
 * The damage of a weapon hit ({@link Hooks.onBeforeDamage}). The changes go to the next hook and to the game when the hook returns.
 */
interface DamageDecision {
    /**
     * @returns the decision's record, whose amount (1/256) and cancelled the setters change; a hook may change them there too
     */
    getRecord(): CoreDecisionRecord;

    /**
     * @returns the damaged unit
     */
    getTarget(): Unit;

    /**
     * @returns the damaged unit's type
     */
    getTargetType(): UnitType;

    /**
     * @returns the damaged unit's owner
     */
    getTargetOwner(): Player;

    /**
     * @returns the attacking unit, or null when unknown
     */
    getSource(): Unit | null;

    /**
     * @returns the player of the attack, or null when unknown
     */
    getSourcePlayer(): Player | null;

    /**
     * @returns the weapon, or null when unknown
     */
    getWeapon(): WeaponType | null;

    /**
     * @returns the damage in shown hit points, with fractions
     */
    getAmount(): number;

    /**
     * Sets the damage.
     *
     * @param amount the damage in shown hit points, at least 0 and rounded to 1/256; an amount outside the range throws a RangeError
     */
    setAmount(amount: number): void;

    /**
     * Cancels the damage.
     */
    cancel(): void;

    /**
     * @returns true once a hook cancelled the damage
     */
    isCancelled(): boolean;
}

/**
 * An order issued to a unit ({@link Hooks.onBeforeOrder}). The changes go to the next hook and to the game when the hook returns.
 */
interface OrderDecision {
    /**
     * @returns the decision's record, whose order, target, targetGeneration, targetX, targetY and rejected the setters change
     */
    getRecord(): CoreDecisionRecord;

    /**
     * @returns the ordered unit
     */
    getUnit(): Unit;

    /**
     * @returns the ordered unit's type
     */
    getUnitType(): UnitType;

    /**
     * @returns the ordered unit's owner
     */
    getOwner(): Player;

    /**
     * @returns the unit type the order builds or trains, or null for none
     */
    getBuildType(): UnitType | null;

    /**
     * @returns true for an order queued with Shift
     */
    isQueued(): boolean;

    /**
     * @returns the order, as the hooks before changed it
     */
    getOrder(): Order;

    /**
     * Replaces the order.
     *
     * @param order the new order
     */
    setOrder(order: Order): void;

    /**
     * @returns the target unit, or null for none
     */
    getTarget(): Unit | null;

    /**
     * @returns the target position in map pixels
     */
    getTargetPosition(): Position;

    /**
     * Replaces the target. A gone unit throws an Error.
     *
     * @param target a unit (the target position becomes its position), a position in map pixels without a target unit, or null for no target unit
     */
    setTarget(target: Unit | Position | null): void;

    /**
     * Rejects the order, so that the unit does not take it.
     */
    reject(): void;

    /**
     * @returns true once a hook rejected the order
     */
    isRejected(): boolean;
}

/**
 * The target an automatic target search found ({@link Hooks.onBeforeTargetAcquired}). The changes go to the next hook and to the game when
 * the hook returns.
 */
interface TargetAcquisitionDecision {
    /**
     * @returns the decision's record, whose target and targetGeneration the setter changes
     */
    getRecord(): CoreDecisionRecord;

    /**
     * @returns the searching unit
     */
    getUnit(): Unit;

    /**
     * @returns the searching unit's type
     */
    getUnitType(): UnitType;

    /**
     * @returns the searching unit's owner
     */
    getOwner(): Player;

    /**
     * @returns the target, as the hooks before changed it, or null for none
     */
    getTarget(): Unit | null;

    /**
     * Replaces the target. A gone unit throws an Error.
     *
     * @param target the new target, or null for no target
     */
    setTarget(target: Unit | null): void;
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// The constants.
// ---------------------------------------------------------------------------------------------------------------------------------------

/**
 * An event kind of the records of {@link CoreEvents.getFrameEvents}: the hook of a kind is on followed by its name.
 */
interface EventKind extends GameType {
}

/**
 * The event kinds, by the names of their hooks without on.
 */
interface EventKindTable extends ConstantTable<EventKind> {
    /** The event of {@link Hooks.onUnitCreated}. */
    readonly UnitCreated: EventKind;
    /** The event of {@link Hooks.onUnitCompleted}. */
    readonly UnitCompleted: EventKind;
    /** The event of {@link Hooks.onUnitDied}. */
    readonly UnitDied: EventKind;
    /** The event of {@link Hooks.onUnitDamaged}. */
    readonly UnitDamaged: EventKind;
    /** The event of {@link Hooks.onOrderIssued}. */
    readonly OrderIssued: EventKind;
    /** The event of {@link Hooks.onTargetAcquired}. */
    readonly TargetAcquired: EventKind;
    /** The event of {@link Hooks.onUnitTrained}. */
    readonly UnitTrained: EventKind;
    /** The event of {@link Hooks.onResearchCompleted}. */
    readonly ResearchCompleted: EventKind;
    /** The event of {@link Hooks.onUpgradeCompleted}. */
    readonly UpgradeCompleted: EventKind;
    /** The event of {@link Hooks.onSpellCast}. */
    readonly SpellCast: EventKind;
    /** The event of {@link Hooks.onOwnerChanged}. */
    readonly OwnerChanged: EventKind;
    /** The event of {@link Hooks.onUnitLoaded}. */
    readonly UnitLoaded: EventKind;
    /** The event of {@link Hooks.onUnitUnloaded}. */
    readonly UnitUnloaded: EventKind;
    /** The event of {@link Hooks.onBulletCreated}. */
    readonly BulletCreated: EventKind;
    /** The event of {@link Hooks.onBulletHit}. */
    readonly BulletHit: EventKind;
    /** The event of {@link Hooks.onPlayerLeft}. */
    readonly PlayerLeft: EventKind;
    /** The event of {@link Hooks.onPlayerResult}. */
    readonly PlayerResult: EventKind;
}

/**
 * The event kinds.
 */
declare const EventKind: EventKindTable;

/**
 * An input kind of the records of {@link CoreLocal.getInputEvents}: the hook of a kind is onLocal followed by its name.
 */
interface InputKind extends GameType {
}

/**
 * The input kinds, by the names of their hooks without onLocal.
 */
interface InputKindTable extends ConstantTable<InputKind> {
    /** A key press ({@link Hooks.onLocalKeyDown}). */
    readonly KeyDown: InputKind;
    /** A key release ({@link Hooks.onLocalKeyUp}). */
    readonly KeyUp: InputKind;
    /** A mouse button press ({@link Hooks.onLocalMouseDown}). */
    readonly MouseDown: InputKind;
    /** A mouse button release ({@link Hooks.onLocalMouseUp}). */
    readonly MouseUp: InputKind;
    /** A submitted chat line ({@link Hooks.onLocalChat}). */
    readonly Chat: InputKind;
    /** A command card button press ({@link Hooks.onLocalButtonPressed}). */
    readonly ButtonPressed: InputKind;
    /** The target of a ScriptTarget button ({@link Hooks.onLocalButtonTargeted}). */
    readonly ButtonTargeted: InputKind;
}

/**
 * The input kinds.
 */
declare const InputKind: InputKindTable;

/**
 * A button condition of SC:R's list, whose id {@link CoreButtons} takes; Script gives the local state of its condition value
 * ({@link Local.setButtonState}).
 */
interface ButtonCondition extends GameType {
}

/**
 * The button conditions, in the order of SC:R's list.
 */
interface ButtonConditionTable extends ConstantTable<ButtonCondition> {
    readonly Always: ButtonCondition;
    readonly ReplaySlowDown: ButtonCondition;
    readonly ReplaySpeedUp: ButtonCondition;
    readonly ReplayPlayPause: ButtonCondition;
    readonly UnitRequirement: ButtonCondition;
    readonly HangarUnitRequirement: ButtonCondition;
    readonly AnyCanMove: ButtonCondition;
    readonly AnyHasRightClick: ButtonCondition;
    readonly AnyCanAttack: ButtonCondition;
    readonly AnyEnabledCanAttack: ButtonCondition;
    readonly TrainingUnit: ButtonCondition;
    readonly BuildingAddon: ButtonCondition;
    readonly IsHatchery: ButtonCondition;
    readonly RallyOrder: ButtonCondition;
    readonly HatcheryRallyOrder: ButtonCondition;
    readonly Incomplete: ButtonCondition;
    readonly Upgrading: ButtonCondition;
    readonly Researching: ButtonCondition;
    readonly AnyNotCarrying: ButtonCondition;
    readonly AnyCarrying: ButtonCondition;
    readonly AnyCanLoad: ButtonCondition;
    readonly AnyHasLoaded: ButtonCondition;
    readonly ResearchRequirement: ButtonCondition;
    readonly TechnologyUseRequirement: ButtonCondition;
    readonly AnyTechnologyUseRequirement: ButtonCondition;
    readonly UpgradeRequirement: ButtonCondition;
    readonly CanCloak: ButtonCondition;
    readonly CanDecloak: ButtonCondition;
    readonly CanCloakOwnTechnology: ButtonCondition;
    readonly CanDecloakOwnTechnology: ButtonCondition;
    readonly CanSiege: ButtonCondition;
    readonly CanUnsiege: ButtonCondition;
    readonly NoneSieged: ButtonCondition;
    readonly NuclearSiloReady: ButtonCondition;
    readonly NotDisabled: ButtonCondition;
    readonly NotGrounded: ButtonCondition;
    readonly CanLand: ButtonCondition;
    readonly CanLiftOff: ButtonCondition;
    readonly CanBurrow: ButtonCondition;
    readonly CanUnburrow: ButtonCondition;
    readonly LurkerAspect: ButtonCondition;
    readonly NoneBurrowed: ButtonCondition;
    readonly NoneBurrowedCanAttack: ButtonCondition;
    readonly NoneBurrowedNotCarrying: ButtonCondition;
    readonly NoneBurrowedCarrying: ButtonCondition;
    readonly SameBurrowState: ButtonCondition;
    readonly NoNydusExit: ButtonCondition;
    readonly ZergBasicStructures: ButtonCondition;
    readonly ZergAdvancedStructures: ButtonCondition;
    readonly CarrierHasInterceptors: ButtonCondition;
    readonly ReaverHasScarabs: ButtonCondition;
    readonly TechnologyUseDisabled: ButtonCondition;
    readonly ArchonWarp: ButtonCondition;
    readonly DarkArchonMeld: ButtonCondition;
    readonly ProtossBasicStructures: ButtonCondition;
    readonly ProtossAdvancedStructures: ButtonCondition;
    readonly AllConstructing: ButtonCondition;
    readonly AnyNotConstructing: ButtonCondition;
    readonly ScvCanGather: ButtonCondition;
    readonly ScvCanReturnCargo: ButtonCondition;
    readonly TerranBasicStructures: ButtonCondition;
    readonly TerranAdvancedStructures: ButtonCondition;
    readonly SingleUnitRequirement: ButtonCondition;
    /** The local state of the condition value ({@link Local.setButtonState}). */
    readonly Script: ButtonCondition;
}

/**
 * The button conditions.
 */
declare const ButtonCondition: ButtonConditionTable;

/**
 * A button action of SC:R's list, whose id {@link CoreButtons} takes; Script and ScriptTarget send no command.
 */
interface ButtonAction extends GameType {
}

/**
 * The button actions, in the order of SC:R's list.
 */
interface ButtonActionTable extends ConstantTable<ButtonAction> {
    readonly Move: ButtonAction;
    readonly Stop: ButtonAction;
    readonly Attack: ButtonAction;
    readonly Patrol: ButtonAction;
    readonly HoldPosition: ButtonAction;
    readonly StimPacks: ButtonAction;
    readonly Cloak: ButtonAction;
    readonly Decloak: ButtonAction;
    readonly UseTechnology: ButtonAction;
    readonly NuclearStrike: ButtonAction;
    readonly Siege: ButtonAction;
    readonly Unsiege: ButtonAction;
    readonly Repair: ButtonAction;
    readonly Gather: ButtonAction;
    readonly ReturnCargo: ButtonAction;
    readonly ChangeButtons: ButtonAction;
    readonly Load: ButtonAction;
    readonly UnloadAll: ButtonAction;
    readonly Heal: ButtonAction;
    readonly UnitMorph: ButtonAction;
    readonly CancelMorph: ButtonAction;
    readonly Burrow: ButtonAction;
    readonly Unburrow: ButtonAction;
    readonly InfestedTerranAttack: ButtonAction;
    readonly DarkArchonMeld: ButtonAction;
    readonly ArchonWarp: ButtonAction;
    readonly CarrierMove: ButtonAction;
    readonly CarrierStop: ButtonAction;
    readonly CarrierAttack: ButtonAction;
    readonly TrainHangarUnit: ButtonAction;
    readonly CancelLast: ButtonAction;
    readonly ReaverStop: ButtonAction;
    readonly ReaverAttack: ButtonAction;
    readonly Train: ButtonAction;
    readonly SetRallyPoint: ButtonAction;
    readonly BuildAddon: ButtonAction;
    readonly Land: ButtonAction;
    readonly LiftOff: ButtonAction;
    readonly CancelAddon: ButtonAction;
    readonly Upgrade: ButtonAction;
    readonly Research: ButtonAction;
    readonly CancelUpgrade: ButtonAction;
    readonly CancelResearch: ButtonAction;
    readonly BuildingAttack: ButtonAction;
    readonly SelectLarva: ButtonAction;
    readonly BuildingMorph: ButtonAction;
    readonly PlaceNydusExit: ButtonAction;
    readonly RechargeShields: ButtonAction;
    readonly PlaceBeacon: ButtonAction;
    readonly CancelTargeting: ButtonAction;
    readonly CancelPlacement: ButtonAction;
    readonly CancelConstruction: ButtonAction;
    readonly CancelInfestation: ButtonAction;
    readonly CancelNuclearStrike: ButtonAction;
    readonly ZergBuild: ButtonAction;
    readonly TerranBuild: ButtonAction;
    readonly ProtossBuild: ButtonAction;
    readonly ReplayPlayPause: ButtonAction;
    readonly ReplaySlowDown: ButtonAction;
    readonly ReplaySpeedUp: ButtonAction;
    /** A press reaches the local input hooks only; it sends no command. */
    readonly Script: ButtonAction;
    /** A press enters a targeting mode whose target reaches the local input hooks only; it sends no command. */
    readonly ScriptTarget: ButtonAction;
}

/**
 * The button actions.
 */
declare const ButtonAction: ButtonActionTable;

/**
 * A state of the Script button condition; its id is the native condition result.
 */
interface ButtonState extends GameType {
}

/**
 * The states of the Script button condition.
 */
interface ButtonStateTable {
    /** The button is shown and works; id 1. */
    readonly Enabled: ButtonState;
    /** The button is not shown; id 0, the state every condition value starts with. */
    readonly Hidden: ButtonState;
    /** The button is shown gray; id -1. */
    readonly Disabled: ButtonState;

    /**
     * @param id the condition result: 1, 0 or -1
     * @returns the state, or null for another value
     */
    get(id: number): ButtonState | null;
}

/**
 * The states of the Script button condition.
 */
declare const ButtonState: ButtonStateTable;

/**
 * The kind of a button's text, the second character of the native button string, which chooses the costs the tooltip shows for the action
 * value.
 */
interface ButtonKind extends GameType {
}

/**
 * The kinds of a button's text, by the costs they show: none, a unit type's, an upgrade's, a tech's use, a tech's research and a morph's.
 */
interface ButtonKindTable extends ConstantTable<ButtonKind> {
    /** No costs; the default. */
    readonly None: ButtonKind;
    readonly UnitProduction: ButtonKind;
    readonly Upgrade: ButtonKind;
    readonly TechnologyUse: ButtonKind;
    readonly Research: ButtonKind;
    readonly Morph: ButtonKind;
}

/**
 * The kinds of a button's text.
 */
declare const ButtonKind: ButtonKindTable;

/**
 * A decision kind of the decision records ({@link CoreDecisionRecord}): the hook of a kind is onBefore followed by its name.
 */
interface DecisionKind extends GameType {
}

/**
 * The decision kinds, by the names of their hooks without onBefore.
 */
interface DecisionKindTable extends ConstantTable<DecisionKind> {
    /** The damage of a weapon hit ({@link Hooks.onBeforeDamage}). */
    readonly Damage: DecisionKind;
    /** An order issued to a unit ({@link Hooks.onBeforeOrder}). */
    readonly Order: DecisionKind;
    /** The target an automatic target search found ({@link Hooks.onBeforeTargetAcquired}). */
    readonly TargetAcquired: DecisionKind;
}

/**
 * The decision kinds.
 */
declare const DecisionKind: DecisionKindTable;

/**
 * The bits of the modifier mask of a local key or mouse button input ({@link LocalInputEvent.getModifiers}).
 */
interface ModifierTable {
    /** 1. */
    readonly Shift: number;
    /** 2. */
    readonly Control: number;
    /** 4. */
    readonly Alt: number;
}

/**
 * The bits of the modifier mask.
 */
declare const Modifier: ModifierTable;

/**
 * The unit groups Any unit, Men, Buildings and Factories of the trigger conditions and actions; their ids are their unit operands, 229 to 232.
 */
interface UnitGroupTable {
    readonly AnyUnit: UnitGroup;
    readonly Men: UnitGroup;
    readonly Buildings: UnitGroup;
    readonly Factories: UnitGroup;
}

/**
 * The unit groups.
 */
declare const UnitGroup: UnitGroupTable;

/**
 * The score types of the trigger conditions and actions.
 */
interface ScoreTypeTable extends ConstantTable<ScoreType> {
    readonly Total: ScoreType;
    readonly Units: ScoreType;
    readonly Buildings: ScoreType;
    readonly UnitsAndBuildings: ScoreType;
    readonly Kills: ScoreType;
    readonly Razings: ScoreType;
    readonly KillsAndRazings: ScoreType;
    readonly Custom: ScoreType;
}

/**
 * The score types.
 */
declare const ScoreType: ScoreTypeTable;

/**
 * The resource types of the trigger conditions and actions.
 */
interface ResourceTypeTable extends ConstantTable<ResourceType> {
    readonly Minerals: ResourceType;
    readonly Gas: ResourceType;
    readonly MineralsAndGas: ResourceType;
    /**
     * Only for the console icon ({@link Console.setResourceIcon}); the resource functions refuse it.
     */
    readonly Supply: ResourceType;
}

/**
 * The resource types.
 */
declare const ResourceType: ResourceTypeTable;

/**
 * The alliance states between two players.
 */
interface AllianceTable extends ConstantTable<Alliance> {
    readonly Enemy: Alliance;
    readonly Ally: Alliance;
    readonly AlliedVictory: Alliance;
}

/**
 * The alliance states.
 */
declare const Alliance: AllianceTable;

/**
 * The game speeds, by their index in the frame duration table.
 */
interface GameSpeedTable extends ConstantTable<GameSpeed> {
    readonly Slowest: GameSpeed;
    readonly Slower: GameSpeed;
    readonly Slow: GameSpeed;
    readonly Normal: GameSpeed;
    readonly Fast: GameSpeed;
    readonly Faster: GameSpeed;
    readonly Fastest: GameSpeed;
}

/**
 * The game speeds.
 */
declare const GameSpeed: GameSpeedTable;

/**
 * The cheats, each with its bit of the cheat flags as its id.
 */
interface CheatTable {
    readonly BlackSheepWall: Cheat;
    readonly OperationCwal: Cheat;
    readonly PowerOverwhelming: Cheat;
    readonly SomethingForNothing: Cheat;
    readonly ShowMeTheMoney: Cheat;
    readonly GameOverMan: Cheat;
    readonly ThereIsNoCowLevel: Cheat;
    readonly StayingAlive: Cheat;
    readonly Ophelia: Cheat;
    readonly TheGathering: Cheat;
    readonly MedievalMan: Cheat;
    readonly ModifyThePhaseVariance: Cheat;
    readonly WarAintWhatItUsedToBe: Cheat;
    readonly FoodForThought: Cheat;
    readonly WhatsMineIsMine: Cheat;
    readonly BreatheDeep: Cheat;
    readonly Noglues: Cheat;
}

/**
 * The cheats.
 */
declare const Cheat: CheatTable;

/**
 * The game types of the game header, ids 0 to 15; the ids without a type are named "Game mode" and the id.
 */
interface GameModeTable extends ConstantTable<GameMode> {
    readonly Melee: GameMode;
    readonly FreeForAll: GameMode;
    readonly OneOnOne: GameMode;
    readonly CaptureTheFlag: GameMode;
    readonly Greed: GameMode;
    readonly Slaughter: GameMode;
    readonly SuddenDeath: GameMode;
    readonly Ladder: GameMode;
    readonly UseMapSettings: GameMode;
    readonly TeamMelee: GameMode;
    readonly TeamFreeForAll: GameMode;
    readonly TeamCaptureTheFlag: GameMode;
    readonly TopVsBottom: GameMode;
}

/**
 * The game types.
 */
declare const GameMode: GameModeTable;

/**
 * The unit sizes of units.dat.
 */
interface UnitSizeTable extends ConstantTable<UnitSize> {
    readonly Independent: UnitSize;
    readonly Small: UnitSize;
    readonly Medium: UnitSize;
    readonly Large: UnitSize;
}

/**
 * The unit sizes.
 */
declare const UnitSize: UnitSizeTable;

/**
 * The damage types of weapons.dat.
 */
interface DamageTypeTable extends ConstantTable<DamageType> {
    readonly Independent: DamageType;
    readonly Explosive: DamageType;
    readonly Concussive: DamageType;
    readonly Normal: DamageType;
    readonly IgnoreArmor: DamageType;
}

/**
 * The damage types.
 */
declare const DamageType: DamageTypeTable;

/**
 * The elevation flags of a location, each with its bit as its id: a set flag leaves the units at its elevation out of the location.
 */
interface LocationFlagTable {
    /** 0x1. */
    readonly LowGround: LocationFlag;
    /** 0x2. */
    readonly MiddleGround: LocationFlag;
    /** 0x4. */
    readonly HighGround: LocationFlag;
    /** 0x8. */
    readonly LowAir: LocationFlag;
    /** 0x10. */
    readonly MiddleAir: LocationFlag;
    /** 0x20. */
    readonly HighAir: LocationFlag;
}

/**
 * The elevation flags of a location.
 */
declare const LocationFlag: LocationFlagTable;

/**
 * A draw function of images.dat, as SC:R's renderer names it; its id is the images.dat value.
 */
interface DrawFunction extends GameType {
}

/**
 * The draw functions, 0 to 17.
 */
interface DrawFunctionTable extends ConstantTable<DrawFunction> {
    readonly Normal: DrawFunction;
    readonly NormalUnchecked: DrawFunction;
    readonly UnseenCloaking: DrawFunction;
    readonly UnseenCloaked: DrawFunction;
    readonly UnseenDecloaking: DrawFunction;
    readonly Cloaking: DrawFunction;
    readonly Cloaked: DrawFunction;
    readonly Decloaking: DrawFunction;
    readonly Distortion: DrawFunction;
    readonly Remapping: DrawFunction;
    readonly Shadow: DrawFunction;
    readonly HealthBar: DrawFunction;
    readonly WarpTexture: DrawFunction;
    readonly SelectionCircle: DrawFunction;
    readonly PlayerColor: DrawFunction;
    readonly UpdateRectangle: DrawFunction;
    readonly Hallucination: DrawFunction;
    readonly WarpFlash: DrawFunction;
}

/**
 * The draw functions.
 */
declare const DrawFunction: DrawFunctionTable;

/**
 * An order state of a bullet ({@link Bullet.getState}).
 */
interface BulletState extends GameType {
}

/**
 * The order states of a bullet.
 */
interface BulletStateTable extends ConstantTable<BulletState> {
    readonly Init: BulletState;
    readonly Move: BulletState;
    readonly Follow: BulletState;
    readonly Bounce: BulletState;
    readonly DamageOverTime: BulletState;
    readonly Dying: BulletState;
    readonly HitNearTarget: BulletState;
}

/**
 * The order states of a bullet.
 */
declare const BulletState: BulletStateTable;

/**
 * An iscript animation of an image ({@link Unit.playAnimation}).
 */
interface Animation extends GameType {
}

/**
 * The iscript animations, in the order of an iscript entry's animation table.
 */
interface AnimationTable extends ConstantTable<Animation> {
    readonly Init: Animation;
    readonly Death: Animation;
    readonly GroundAttackInit: Animation;
    readonly AirAttackInit: Animation;
    readonly Unused1: Animation;
    readonly GroundAttackRepeat: Animation;
    readonly AirAttackRepeat: Animation;
    readonly CastSpell: Animation;
    readonly GroundAttackToIdle: Animation;
    readonly AirAttackToIdle: Animation;
    readonly Unused2: Animation;
    readonly Walking: Animation;
    readonly WalkingToIdle: Animation;
    readonly SpecialState1: Animation;
    readonly SpecialState2: Animation;
    readonly AlmostBuilt: Animation;
    readonly Built: Animation;
    readonly Landing: Animation;
    readonly LiftOff: Animation;
    readonly IsWorking: Animation;
    readonly WorkingToIdle: Animation;
    readonly WarpIn: Animation;
    readonly Unused3: Animation;
    readonly StarEditInit: Animation;
    readonly Disable: Animation;
    readonly Burrow: Animation;
    readonly Unburrow: Animation;
    readonly Enable: Animation;
}

/**
 * The iscript animations.
 */
declare const Animation: AnimationTable;

/**
 * The mouse buttons of a local mouse button input.
 */
interface MouseButtonTable extends ConstantTable<MouseButton> {
    readonly Left: MouseButton;
    readonly Right: MouseButton;
    readonly Middle: MouseButton;
}

/**
 * The mouse buttons.
 */
declare const MouseButton: MouseButtonTable;

/**
 * The keys by Windows virtual-key code, 0 to 255: the named ones, every other code by {@link ConstantTable.get}, named "Key" and the code.
 */
interface KeyTable extends ConstantTable<Key> {
    // GENERATED Key
    /** 8 Backspace */ readonly Backspace: Key;
    /** 9 Tab */ readonly Tab: Key;
    /** 13 Enter */ readonly Enter: Key;
    /** 16 Shift */ readonly Shift: Key;
    /** 17 Control */ readonly Control: Key;
    /** 18 Alt */ readonly Alt: Key;
    /** 19 Pause */ readonly Pause: Key;
    /** 20 CapsLock */ readonly CapsLock: Key;
    /** 27 Escape */ readonly Escape: Key;
    /** 32 Space */ readonly Space: Key;
    /** 33 PageUp */ readonly PageUp: Key;
    /** 34 PageDown */ readonly PageDown: Key;
    /** 35 End */ readonly End: Key;
    /** 36 Home */ readonly Home: Key;
    /** 37 Left */ readonly Left: Key;
    /** 38 Up */ readonly Up: Key;
    /** 39 Right */ readonly Right: Key;
    /** 40 Down */ readonly Down: Key;
    /** 45 Insert */ readonly Insert: Key;
    /** 46 Delete */ readonly Delete: Key;
    /** 48 Digit0 */ readonly Digit0: Key;
    /** 49 Digit1 */ readonly Digit1: Key;
    /** 50 Digit2 */ readonly Digit2: Key;
    /** 51 Digit3 */ readonly Digit3: Key;
    /** 52 Digit4 */ readonly Digit4: Key;
    /** 53 Digit5 */ readonly Digit5: Key;
    /** 54 Digit6 */ readonly Digit6: Key;
    /** 55 Digit7 */ readonly Digit7: Key;
    /** 56 Digit8 */ readonly Digit8: Key;
    /** 57 Digit9 */ readonly Digit9: Key;
    /** 65 A */ readonly A: Key;
    /** 66 B */ readonly B: Key;
    /** 67 C */ readonly C: Key;
    /** 68 D */ readonly D: Key;
    /** 69 E */ readonly E: Key;
    /** 70 F */ readonly F: Key;
    /** 71 G */ readonly G: Key;
    /** 72 H */ readonly H: Key;
    /** 73 I */ readonly I: Key;
    /** 74 J */ readonly J: Key;
    /** 75 K */ readonly K: Key;
    /** 76 L */ readonly L: Key;
    /** 77 M */ readonly M: Key;
    /** 78 N */ readonly N: Key;
    /** 79 O */ readonly O: Key;
    /** 80 P */ readonly P: Key;
    /** 81 Q */ readonly Q: Key;
    /** 82 R */ readonly R: Key;
    /** 83 S */ readonly S: Key;
    /** 84 T */ readonly T: Key;
    /** 85 U */ readonly U: Key;
    /** 86 V */ readonly V: Key;
    /** 87 W */ readonly W: Key;
    /** 88 X */ readonly X: Key;
    /** 89 Y */ readonly Y: Key;
    /** 90 Z */ readonly Z: Key;
    /** 96 Numpad0 */ readonly Numpad0: Key;
    /** 97 Numpad1 */ readonly Numpad1: Key;
    /** 98 Numpad2 */ readonly Numpad2: Key;
    /** 99 Numpad3 */ readonly Numpad3: Key;
    /** 100 Numpad4 */ readonly Numpad4: Key;
    /** 101 Numpad5 */ readonly Numpad5: Key;
    /** 102 Numpad6 */ readonly Numpad6: Key;
    /** 103 Numpad7 */ readonly Numpad7: Key;
    /** 104 Numpad8 */ readonly Numpad8: Key;
    /** 105 Numpad9 */ readonly Numpad9: Key;
    /** 106 NumpadMultiply */ readonly NumpadMultiply: Key;
    /** 107 NumpadAdd */ readonly NumpadAdd: Key;
    /** 109 NumpadSubtract */ readonly NumpadSubtract: Key;
    /** 110 NumpadDecimal */ readonly NumpadDecimal: Key;
    /** 111 NumpadDivide */ readonly NumpadDivide: Key;
    /** 112 F1 */ readonly F1: Key;
    /** 113 F2 */ readonly F2: Key;
    /** 114 F3 */ readonly F3: Key;
    /** 115 F4 */ readonly F4: Key;
    /** 116 F5 */ readonly F5: Key;
    /** 117 F6 */ readonly F6: Key;
    /** 118 F7 */ readonly F7: Key;
    /** 119 F8 */ readonly F8: Key;
    /** 120 F9 */ readonly F9: Key;
    /** 121 F10 */ readonly F10: Key;
    /** 122 F11 */ readonly F11: Key;
    /** 123 F12 */ readonly F12: Key;
    /** 186 Semicolon */ readonly Semicolon: Key;
    /** 187 Equal */ readonly Equal: Key;
    /** 188 Comma */ readonly Comma: Key;
    /** 189 Minus */ readonly Minus: Key;
    /** 190 Period */ readonly Period: Key;
    /** 191 Slash */ readonly Slash: Key;
    /** 192 Backquote */ readonly Backquote: Key;
    /** 219 BracketLeft */ readonly BracketLeft: Key;
    /** 220 Backslash */ readonly Backslash: Key;
    /** 221 BracketRight */ readonly BracketRight: Key;
    /** 222 Quote */ readonly Quote: Key;
}

/**
 * The keys.
 */
declare const Key: KeyTable;

/**
 * The image types by their names from the GRP paths of the installation; the names are known when the game starts.
 */
interface ImageTypeNames {
    readonly [name: string]: ImageType;
}

/**
 * The image types of images.dat.
 */
declare const ImageType: ConstantTable<ImageType> & ImageTypeNames;

/**
 * The sprite types by the names of their image types; the names are known when the game starts.
 */
interface SpriteTypeNames {
    readonly [name: string]: SpriteType;
}

/**
 * The sprite types of sprites.dat.
 */
declare const SpriteType: ConstantTable<SpriteType> & SpriteTypeNames;

/**
 * The flingy types by the names of their sprite types; the names are known when the game starts.
 */
interface FlingyTypeNames {
    readonly [name: string]: FlingyType;
}

/**
 * The flingy types of flingy.dat.
 */
declare const FlingyType: ConstantTable<FlingyType> & FlingyTypeNames;

/**
 * The unit types of units.dat, by the identifiers of their original names ("Terran Marine" is TerranMarine).
 */
interface UnitTypeTable extends ConstantTable<UnitType> {
    // GENERATED UnitType
    /** 0 Terran Marine */ readonly TerranMarine: UnitType;
    /** 1 Terran Ghost */ readonly TerranGhost: UnitType;
    /** 2 Terran Vulture */ readonly TerranVulture: UnitType;
    /** 3 Terran Goliath */ readonly TerranGoliath: UnitType;
    /** 4 Terran Goliath Turret */ readonly TerranGoliathTurret: UnitType;
    /** 5 Terran Siege Tank Tank Mode */ readonly TerranSiegeTankTankMode: UnitType;
    /** 6 Terran Siege Tank Tank Mode Turret */ readonly TerranSiegeTankTankModeTurret: UnitType;
    /** 7 Terran SCV */ readonly TerranSCV: UnitType;
    /** 8 Terran Wraith */ readonly TerranWraith: UnitType;
    /** 9 Terran Science Vessel */ readonly TerranScienceVessel: UnitType;
    /** 10 Hero Gui Montag */ readonly HeroGuiMontag: UnitType;
    /** 11 Terran Dropship */ readonly TerranDropship: UnitType;
    /** 12 Terran Battlecruiser */ readonly TerranBattlecruiser: UnitType;
    /** 13 Terran Vulture Spider Mine */ readonly TerranVultureSpiderMine: UnitType;
    /** 14 Terran Nuclear Missile */ readonly TerranNuclearMissile: UnitType;
    /** 15 Terran Civilian */ readonly TerranCivilian: UnitType;
    /** 16 Hero Sarah Kerrigan */ readonly HeroSarahKerrigan: UnitType;
    /** 17 Hero Alan Schezar */ readonly HeroAlanSchezar: UnitType;
    /** 18 Hero Alan Schezar Turret */ readonly HeroAlanSchezarTurret: UnitType;
    /** 19 Hero Jim Raynor Vulture */ readonly HeroJimRaynorVulture: UnitType;
    /** 20 Hero Jim Raynor Marine */ readonly HeroJimRaynorMarine: UnitType;
    /** 21 Hero Tom Kazansky */ readonly HeroTomKazansky: UnitType;
    /** 22 Hero Magellan */ readonly HeroMagellan: UnitType;
    /** 23 Hero Edmund Duke Tank Mode */ readonly HeroEdmundDukeTankMode: UnitType;
    /** 24 Hero Edmund Duke Tank Mode Turret */ readonly HeroEdmundDukeTankModeTurret: UnitType;
    /** 25 Hero Edmund Duke Siege Mode */ readonly HeroEdmundDukeSiegeMode: UnitType;
    /** 26 Hero Edmund Duke Siege Mode Turret */ readonly HeroEdmundDukeSiegeModeTurret: UnitType;
    /** 27 Hero Arcturus Mengsk */ readonly HeroArcturusMengsk: UnitType;
    /** 28 Hero Hyperion */ readonly HeroHyperion: UnitType;
    /** 29 Hero Norad II */ readonly HeroNoradII: UnitType;
    /** 30 Terran Siege Tank Siege Mode */ readonly TerranSiegeTankSiegeMode: UnitType;
    /** 31 Terran Siege Tank Siege Mode Turret */ readonly TerranSiegeTankSiegeModeTurret: UnitType;
    /** 32 Terran Firebat */ readonly TerranFirebat: UnitType;
    /** 33 Spell Scanner Sweep */ readonly SpellScannerSweep: UnitType;
    /** 34 Terran Medic */ readonly TerranMedic: UnitType;
    /** 35 Zerg Larva */ readonly ZergLarva: UnitType;
    /** 36 Zerg Egg */ readonly ZergEgg: UnitType;
    /** 37 Zerg Zergling */ readonly ZergZergling: UnitType;
    /** 38 Zerg Hydralisk */ readonly ZergHydralisk: UnitType;
    /** 39 Zerg Ultralisk */ readonly ZergUltralisk: UnitType;
    /** 40 Zerg Broodling */ readonly ZergBroodling: UnitType;
    /** 41 Zerg Drone */ readonly ZergDrone: UnitType;
    /** 42 Zerg Overlord */ readonly ZergOverlord: UnitType;
    /** 43 Zerg Mutalisk */ readonly ZergMutalisk: UnitType;
    /** 44 Zerg Guardian */ readonly ZergGuardian: UnitType;
    /** 45 Zerg Queen */ readonly ZergQueen: UnitType;
    /** 46 Zerg Defiler */ readonly ZergDefiler: UnitType;
    /** 47 Zerg Scourge */ readonly ZergScourge: UnitType;
    /** 48 Hero Torrasque */ readonly HeroTorrasque: UnitType;
    /** 49 Hero Matriarch */ readonly HeroMatriarch: UnitType;
    /** 50 Zerg Infested Terran */ readonly ZergInfestedTerran: UnitType;
    /** 51 Hero Infested Kerrigan */ readonly HeroInfestedKerrigan: UnitType;
    /** 52 Hero Unclean One */ readonly HeroUncleanOne: UnitType;
    /** 53 Hero Hunter Killer */ readonly HeroHunterKiller: UnitType;
    /** 54 Hero Devouring One */ readonly HeroDevouringOne: UnitType;
    /** 55 Hero Kukulza Mutalisk */ readonly HeroKukulzaMutalisk: UnitType;
    /** 56 Hero Kukulza Guardian */ readonly HeroKukulzaGuardian: UnitType;
    /** 57 Hero Yggdrasill */ readonly HeroYggdrasill: UnitType;
    /** 58 Terran Valkyrie */ readonly TerranValkyrie: UnitType;
    /** 59 Zerg Cocoon */ readonly ZergCocoon: UnitType;
    /** 60 Protoss Corsair */ readonly ProtossCorsair: UnitType;
    /** 61 Protoss Dark Templar */ readonly ProtossDarkTemplar: UnitType;
    /** 62 Zerg Devourer */ readonly ZergDevourer: UnitType;
    /** 63 Protoss Dark Archon */ readonly ProtossDarkArchon: UnitType;
    /** 64 Protoss Probe */ readonly ProtossProbe: UnitType;
    /** 65 Protoss Zealot */ readonly ProtossZealot: UnitType;
    /** 66 Protoss Dragoon */ readonly ProtossDragoon: UnitType;
    /** 67 Protoss High Templar */ readonly ProtossHighTemplar: UnitType;
    /** 68 Protoss Archon */ readonly ProtossArchon: UnitType;
    /** 69 Protoss Shuttle */ readonly ProtossShuttle: UnitType;
    /** 70 Protoss Scout */ readonly ProtossScout: UnitType;
    /** 71 Protoss Arbiter */ readonly ProtossArbiter: UnitType;
    /** 72 Protoss Carrier */ readonly ProtossCarrier: UnitType;
    /** 73 Protoss Interceptor */ readonly ProtossInterceptor: UnitType;
    /** 74 Hero Dark Templar */ readonly HeroDarkTemplar: UnitType;
    /** 75 Hero Zeratul */ readonly HeroZeratul: UnitType;
    /** 76 Hero Tassadar Zeratul Archon */ readonly HeroTassadarZeratulArchon: UnitType;
    /** 77 Hero Fenix Zealot */ readonly HeroFenixZealot: UnitType;
    /** 78 Hero Fenix Dragoon */ readonly HeroFenixDragoon: UnitType;
    /** 79 Hero Tassadar */ readonly HeroTassadar: UnitType;
    /** 80 Hero Mojo */ readonly HeroMojo: UnitType;
    /** 81 Hero Warbringer */ readonly HeroWarbringer: UnitType;
    /** 82 Hero Gantrithor */ readonly HeroGantrithor: UnitType;
    /** 83 Protoss Reaver */ readonly ProtossReaver: UnitType;
    /** 84 Protoss Observer */ readonly ProtossObserver: UnitType;
    /** 85 Protoss Scarab */ readonly ProtossScarab: UnitType;
    /** 86 Hero Danimoth */ readonly HeroDanimoth: UnitType;
    /** 87 Hero Aldaris */ readonly HeroAldaris: UnitType;
    /** 88 Hero Artanis */ readonly HeroArtanis: UnitType;
    /** 89 Critter Rhynadon */ readonly CritterRhynadon: UnitType;
    /** 90 Critter Bengalaas */ readonly CritterBengalaas: UnitType;
    /** 91 Special Cargo Ship */ readonly SpecialCargoShip: UnitType;
    /** 92 Special Mercenary Gunship */ readonly SpecialMercenaryGunship: UnitType;
    /** 93 Critter Scantid */ readonly CritterScantid: UnitType;
    /** 94 Critter Kakaru */ readonly CritterKakaru: UnitType;
    /** 95 Critter Ragnasaur */ readonly CritterRagnasaur: UnitType;
    /** 96 Critter Ursadon */ readonly CritterUrsadon: UnitType;
    /** 97 Zerg Lurker Egg */ readonly ZergLurkerEgg: UnitType;
    /** 98 Hero Raszagal */ readonly HeroRaszagal: UnitType;
    /** 99 Hero Samir Duran */ readonly HeroSamirDuran: UnitType;
    /** 100 Hero Alexei Stukov */ readonly HeroAlexeiStukov: UnitType;
    /** 101 Special Map Revealer */ readonly SpecialMapRevealer: UnitType;
    /** 102 Hero Gerard DuGalle */ readonly HeroGerardDuGalle: UnitType;
    /** 103 Zerg Lurker */ readonly ZergLurker: UnitType;
    /** 104 Hero Infested Duran */ readonly HeroInfestedDuran: UnitType;
    /** 105 Spell Disruption Web */ readonly SpellDisruptionWeb: UnitType;
    /** 106 Terran Command Center */ readonly TerranCommandCenter: UnitType;
    /** 107 Terran Comsat Station */ readonly TerranComsatStation: UnitType;
    /** 108 Terran Nuclear Silo */ readonly TerranNuclearSilo: UnitType;
    /** 109 Terran Supply Depot */ readonly TerranSupplyDepot: UnitType;
    /** 110 Terran Refinery */ readonly TerranRefinery: UnitType;
    /** 111 Terran Barracks */ readonly TerranBarracks: UnitType;
    /** 112 Terran Academy */ readonly TerranAcademy: UnitType;
    /** 113 Terran Factory */ readonly TerranFactory: UnitType;
    /** 114 Terran Starport */ readonly TerranStarport: UnitType;
    /** 115 Terran Control Tower */ readonly TerranControlTower: UnitType;
    /** 116 Terran Science Facility */ readonly TerranScienceFacility: UnitType;
    /** 117 Terran Covert Ops */ readonly TerranCovertOps: UnitType;
    /** 118 Terran Physics Lab */ readonly TerranPhysicsLab: UnitType;
    /** 119 Unused Terran1 */ readonly UnusedTerran1: UnitType;
    /** 120 Terran Machine Shop */ readonly TerranMachineShop: UnitType;
    /** 121 Unused Terran2 */ readonly UnusedTerran2: UnitType;
    /** 122 Terran Engineering Bay */ readonly TerranEngineeringBay: UnitType;
    /** 123 Terran Armory */ readonly TerranArmory: UnitType;
    /** 124 Terran Missile Turret */ readonly TerranMissileTurret: UnitType;
    /** 125 Terran Bunker */ readonly TerranBunker: UnitType;
    /** 126 Special Crashed Norad II */ readonly SpecialCrashedNoradII: UnitType;
    /** 127 Special Ion Cannon */ readonly SpecialIonCannon: UnitType;
    /** 128 Powerup Uraj Crystal */ readonly PowerupUrajCrystal: UnitType;
    /** 129 Powerup Khalis Crystal */ readonly PowerupKhalisCrystal: UnitType;
    /** 130 Zerg Infested Command Center */ readonly ZergInfestedCommandCenter: UnitType;
    /** 131 Zerg Hatchery */ readonly ZergHatchery: UnitType;
    /** 132 Zerg Lair */ readonly ZergLair: UnitType;
    /** 133 Zerg Hive */ readonly ZergHive: UnitType;
    /** 134 Zerg Nydus Canal */ readonly ZergNydusCanal: UnitType;
    /** 135 Zerg Hydralisk Den */ readonly ZergHydraliskDen: UnitType;
    /** 136 Zerg Defiler Mound */ readonly ZergDefilerMound: UnitType;
    /** 137 Zerg Greater Spire */ readonly ZergGreaterSpire: UnitType;
    /** 138 Zerg Queens Nest */ readonly ZergQueensNest: UnitType;
    /** 139 Zerg Evolution Chamber */ readonly ZergEvolutionChamber: UnitType;
    /** 140 Zerg Ultralisk Cavern */ readonly ZergUltraliskCavern: UnitType;
    /** 141 Zerg Spire */ readonly ZergSpire: UnitType;
    /** 142 Zerg Spawning Pool */ readonly ZergSpawningPool: UnitType;
    /** 143 Zerg Creep Colony */ readonly ZergCreepColony: UnitType;
    /** 144 Zerg Spore Colony */ readonly ZergSporeColony: UnitType;
    /** 145 Unused Zerg1 */ readonly UnusedZerg1: UnitType;
    /** 146 Zerg Sunken Colony */ readonly ZergSunkenColony: UnitType;
    /** 147 Special Overmind With Shell */ readonly SpecialOvermindWithShell: UnitType;
    /** 148 Special Overmind */ readonly SpecialOvermind: UnitType;
    /** 149 Zerg Extractor */ readonly ZergExtractor: UnitType;
    /** 150 Special Mature Chrysalis */ readonly SpecialMatureChrysalis: UnitType;
    /** 151 Special Cerebrate */ readonly SpecialCerebrate: UnitType;
    /** 152 Special Cerebrate Daggoth */ readonly SpecialCerebrateDaggoth: UnitType;
    /** 153 Unused Zerg2 */ readonly UnusedZerg2: UnitType;
    /** 154 Protoss Nexus */ readonly ProtossNexus: UnitType;
    /** 155 Protoss Robotics Facility */ readonly ProtossRoboticsFacility: UnitType;
    /** 156 Protoss Pylon */ readonly ProtossPylon: UnitType;
    /** 157 Protoss Assimilator */ readonly ProtossAssimilator: UnitType;
    /** 158 Unused Protoss1 */ readonly UnusedProtoss1: UnitType;
    /** 159 Protoss Observatory */ readonly ProtossObservatory: UnitType;
    /** 160 Protoss Gateway */ readonly ProtossGateway: UnitType;
    /** 161 Unused Protoss2 */ readonly UnusedProtoss2: UnitType;
    /** 162 Protoss Photon Cannon */ readonly ProtossPhotonCannon: UnitType;
    /** 163 Protoss Citadel of Adun */ readonly ProtossCitadelOfAdun: UnitType;
    /** 164 Protoss Cybernetics Core */ readonly ProtossCyberneticsCore: UnitType;
    /** 165 Protoss Templar Archives */ readonly ProtossTemplarArchives: UnitType;
    /** 166 Protoss Forge */ readonly ProtossForge: UnitType;
    /** 167 Protoss Stargate */ readonly ProtossStargate: UnitType;
    /** 168 Special Stasis Cell Prison */ readonly SpecialStasisCellPrison: UnitType;
    /** 169 Protoss Fleet Beacon */ readonly ProtossFleetBeacon: UnitType;
    /** 170 Protoss Arbiter Tribunal */ readonly ProtossArbiterTribunal: UnitType;
    /** 171 Protoss Robotics Support Bay */ readonly ProtossRoboticsSupportBay: UnitType;
    /** 172 Protoss Shield Battery */ readonly ProtossShieldBattery: UnitType;
    /** 173 Special Khaydarin Crystal Form */ readonly SpecialKhaydarinCrystalForm: UnitType;
    /** 174 Special Protoss Temple */ readonly SpecialProtossTemple: UnitType;
    /** 175 Special XelNaga Temple */ readonly SpecialXelNagaTemple: UnitType;
    /** 176 Resource Mineral Field */ readonly ResourceMineralField: UnitType;
    /** 177 Resource Mineral Field Type 2 */ readonly ResourceMineralFieldType2: UnitType;
    /** 178 Resource Mineral Field Type 3 */ readonly ResourceMineralFieldType3: UnitType;
    /** 179 Unused Cave */ readonly UnusedCave: UnitType;
    /** 180 Unused Cave In */ readonly UnusedCaveIn: UnitType;
    /** 181 Unused Cantina */ readonly UnusedCantina: UnitType;
    /** 182 Unused Mining Platform */ readonly UnusedMiningPlatform: UnitType;
    /** 183 Unused Independant Command Center */ readonly UnusedIndependantCommandCenter: UnitType;
    /** 184 Special Independant Starport */ readonly SpecialIndependantStarport: UnitType;
    /** 185 Unused Independant Jump Gate */ readonly UnusedIndependantJumpGate: UnitType;
    /** 186 Unused Ruins */ readonly UnusedRuins: UnitType;
    /** 187 Unused Khaydarin Crystal Formation */ readonly UnusedKhaydarinCrystalFormation: UnitType;
    /** 188 Resource Vespene Geyser */ readonly ResourceVespeneGeyser: UnitType;
    /** 189 Special Warp Gate */ readonly SpecialWarpGate: UnitType;
    /** 190 Special Psi Disrupter */ readonly SpecialPsiDisrupter: UnitType;
    /** 191 Unused Zerg Marker */ readonly UnusedZergMarker: UnitType;
    /** 192 Unused Terran Marker */ readonly UnusedTerranMarker: UnitType;
    /** 193 Unused Protoss Marker */ readonly UnusedProtossMarker: UnitType;
    /** 194 Special Zerg Beacon */ readonly SpecialZergBeacon: UnitType;
    /** 195 Special Terran Beacon */ readonly SpecialTerranBeacon: UnitType;
    /** 196 Special Protoss Beacon */ readonly SpecialProtossBeacon: UnitType;
    /** 197 Special Zerg Flag Beacon */ readonly SpecialZergFlagBeacon: UnitType;
    /** 198 Special Terran Flag Beacon */ readonly SpecialTerranFlagBeacon: UnitType;
    /** 199 Special Protoss Flag Beacon */ readonly SpecialProtossFlagBeacon: UnitType;
    /** 200 Special Power Generator */ readonly SpecialPowerGenerator: UnitType;
    /** 201 Special Overmind Cocoon */ readonly SpecialOvermindCocoon: UnitType;
    /** 202 Spell Dark Swarm */ readonly SpellDarkSwarm: UnitType;
    /** 203 Special Floor Missile Trap */ readonly SpecialFloorMissileTrap: UnitType;
    /** 204 Special Floor Hatch */ readonly SpecialFloorHatch: UnitType;
    /** 205 Special Upper Level Door */ readonly SpecialUpperLevelDoor: UnitType;
    /** 206 Special Right Upper Level Door */ readonly SpecialRightUpperLevelDoor: UnitType;
    /** 207 Special Pit Door */ readonly SpecialPitDoor: UnitType;
    /** 208 Special Right Pit Door */ readonly SpecialRightPitDoor: UnitType;
    /** 209 Special Floor Gun Trap */ readonly SpecialFloorGunTrap: UnitType;
    /** 210 Special Wall Missile Trap */ readonly SpecialWallMissileTrap: UnitType;
    /** 211 Special Wall Flame Trap */ readonly SpecialWallFlameTrap: UnitType;
    /** 212 Special Right Wall Missile Trap */ readonly SpecialRightWallMissileTrap: UnitType;
    /** 213 Special Right Wall Flame Trap */ readonly SpecialRightWallFlameTrap: UnitType;
    /** 214 Special Start Location */ readonly SpecialStartLocation: UnitType;
    /** 215 Powerup Flag */ readonly PowerupFlag: UnitType;
    /** 216 Powerup Young Chrysalis */ readonly PowerupYoungChrysalis: UnitType;
    /** 217 Powerup Psi Emitter */ readonly PowerupPsiEmitter: UnitType;
    /** 218 Powerup Data Disk */ readonly PowerupDataDisk: UnitType;
    /** 219 Powerup Khaydarin Crystal */ readonly PowerupKhaydarinCrystal: UnitType;
    /** 220 Powerup Mineral Cluster Type 1 */ readonly PowerupMineralClusterType1: UnitType;
    /** 221 Powerup Mineral Cluster Type 2 */ readonly PowerupMineralClusterType2: UnitType;
    /** 222 Powerup Protoss Gas Orb Type 1 */ readonly PowerupProtossGasOrbType1: UnitType;
    /** 223 Powerup Protoss Gas Orb Type 2 */ readonly PowerupProtossGasOrbType2: UnitType;
    /** 224 Powerup Zerg Gas Sac Type 1 */ readonly PowerupZergGasSacType1: UnitType;
    /** 225 Powerup Zerg Gas Sac Type 2 */ readonly PowerupZergGasSacType2: UnitType;
    /** 226 Powerup Terran Gas Tank Type 1 */ readonly PowerupTerranGasTankType1: UnitType;
    /** 227 Powerup Terran Gas Tank Type 2 */ readonly PowerupTerranGasTankType2: UnitType;
}

/**
 * The unit types.
 */
declare const UnitType: UnitTypeTable;

/**
 * The weapon types of weapons.dat, by the identifiers of their original names.
 */
interface WeaponTypeTable extends ConstantTable<WeaponType> {
    // GENERATED WeaponType
    /** 0 Gauss Rifle */ readonly GaussRifle: WeaponType;
    /** 1 Gauss Rifle Jim Raynor */ readonly GaussRifleJimRaynor: WeaponType;
    /** 2 C 10 Canister Rifle */ readonly C10CanisterRifle: WeaponType;
    /** 3 C 10 Canister Rifle Sarah Kerrigan */ readonly C10CanisterRifleSarahKerrigan: WeaponType;
    /** 4 Fragmentation Grenade */ readonly FragmentationGrenade: WeaponType;
    /** 5 Fragmentation Grenade Jim Raynor */ readonly FragmentationGrenadeJimRaynor: WeaponType;
    /** 6 Spider Mines */ readonly SpiderMines: WeaponType;
    /** 7 Twin Autocannons */ readonly TwinAutocannons: WeaponType;
    /** 8 Hellfire Missile Pack */ readonly HellfireMissilePack: WeaponType;
    /** 9 Twin Autocannons Alan Schezar */ readonly TwinAutocannonsAlanSchezar: WeaponType;
    /** 10 Hellfire Missile Pack Alan Schezar */ readonly HellfireMissilePackAlanSchezar: WeaponType;
    /** 11 Arclite Cannon */ readonly ArcliteCannon: WeaponType;
    /** 12 Arclite Cannon Edmund Duke */ readonly ArcliteCannonEdmundDuke: WeaponType;
    /** 13 Fusion Cutter */ readonly FusionCutter: WeaponType;
    /** 14 unknown entry 14 */ readonly UnknownEntry14: WeaponType;
    /** 15 Gemini Missiles */ readonly GeminiMissiles: WeaponType;
    /** 16 Burst Lasers */ readonly BurstLasers: WeaponType;
    /** 17 Gemini Missiles Tom Kazansky */ readonly GeminiMissilesTomKazansky: WeaponType;
    /** 18 Burst Lasers Tom Kazansky */ readonly BurstLasersTomKazansky: WeaponType;
    /** 19 ATS Laser Battery */ readonly ATSLaserBattery: WeaponType;
    /** 20 ATA Laser Battery */ readonly ATALaserBattery: WeaponType;
    /** 21 ATS Laser Battery Hero */ readonly ATSLaserBatteryHero: WeaponType;
    /** 22 ATA Laser Battery Hero */ readonly ATALaserBatteryHero: WeaponType;
    /** 23 ATS Laser Battery Hyperion */ readonly ATSLaserBatteryHyperion: WeaponType;
    /** 24 ATA Laser Battery Hyperion */ readonly ATALaserBatteryHyperion: WeaponType;
    /** 25 Flame Thrower */ readonly FlameThrower: WeaponType;
    /** 26 Flame Thrower Gui Montag */ readonly FlameThrowerGuiMontag: WeaponType;
    /** 27 Arclite Shock Cannon */ readonly ArcliteShockCannon: WeaponType;
    /** 28 Arclite Shock Cannon Edmund Duke */ readonly ArcliteShockCannonEdmundDuke: WeaponType;
    /** 29 Longbolt Missile */ readonly LongboltMissile: WeaponType;
    /** 30 Yamato Gun */ readonly YamatoGun: WeaponType;
    /** 31 Nuclear Strike */ readonly NuclearStrike: WeaponType;
    /** 32 Lockdown */ readonly Lockdown: WeaponType;
    /** 33 EMP Shockwave */ readonly EMPShockwave: WeaponType;
    /** 34 Irradiate */ readonly Irradiate: WeaponType;
    /** 35 Claws */ readonly Claws: WeaponType;
    /** 36 Claws Devouring One */ readonly ClawsDevouringOne: WeaponType;
    /** 37 Claws Infested Kerrigan */ readonly ClawsInfestedKerrigan: WeaponType;
    /** 38 Needle Spines */ readonly NeedleSpines: WeaponType;
    /** 39 Needle Spines Hunter Killer */ readonly NeedleSpinesHunterKiller: WeaponType;
    /** 40 Kaiser Blades */ readonly KaiserBlades: WeaponType;
    /** 41 Kaiser Blades Torrasque */ readonly KaiserBladesTorrasque: WeaponType;
    /** 42 Toxic Spores */ readonly ToxicSpores: WeaponType;
    /** 43 Spines */ readonly Spines: WeaponType;
    /** 44 unknown entry 44 */ readonly UnknownEntry44: WeaponType;
    /** 45 unknown entry 45 */ readonly UnknownEntry45: WeaponType;
    /** 46 Acid Spore */ readonly AcidSpore: WeaponType;
    /** 47 Acid Spore Kukulza */ readonly AcidSporeKukulza: WeaponType;
    /** 48 Glave Wurm */ readonly GlaveWurm: WeaponType;
    /** 49 Glave Wurm Kukulza */ readonly GlaveWurmKukulza: WeaponType;
    /** 50 unknown entry 50 */ readonly UnknownEntry50: WeaponType;
    /** 51 unknown entry 51 */ readonly UnknownEntry51: WeaponType;
    /** 52 Seeker Spores */ readonly SeekerSpores: WeaponType;
    /** 53 Subterranean Tentacle */ readonly SubterraneanTentacle: WeaponType;
    /** 54 Suicide Infested Terran */ readonly SuicideInfestedTerran: WeaponType;
    /** 55 Suicide Scourge */ readonly SuicideScourge: WeaponType;
    /** 56 Parasite */ readonly Parasite: WeaponType;
    /** 57 Spawn Broodlings */ readonly SpawnBroodlings: WeaponType;
    /** 58 Ensnare */ readonly Ensnare: WeaponType;
    /** 59 Dark Swarm */ readonly DarkSwarm: WeaponType;
    /** 60 Plague */ readonly Plague: WeaponType;
    /** 61 Consume */ readonly Consume: WeaponType;
    /** 62 Particle Beam */ readonly ParticleBeam: WeaponType;
    /** 63 unknown entry 63 */ readonly UnknownEntry63: WeaponType;
    /** 64 Psi Blades */ readonly PsiBlades: WeaponType;
    /** 65 Psi Blades Fenix */ readonly PsiBladesFenix: WeaponType;
    /** 66 Phase Disruptor */ readonly PhaseDisruptor: WeaponType;
    /** 67 Phase Disruptor Fenix */ readonly PhaseDisruptorFenix: WeaponType;
    /** 68 unknown entry 68 */ readonly UnknownEntry68: WeaponType;
    /** 69 Psi Assault */ readonly PsiAssault: WeaponType;
    /** 70 Psionic Shockwave */ readonly PsionicShockwave: WeaponType;
    /** 71 Psionic Shockwave TZ Archon */ readonly PsionicShockwaveTZArchon: WeaponType;
    /** 72 unknown entry 72 */ readonly UnknownEntry72: WeaponType;
    /** 73 Dual Photon Blasters */ readonly DualPhotonBlasters: WeaponType;
    /** 74 Anti Matter Missiles */ readonly AntiMatterMissiles: WeaponType;
    /** 75 Dual Photon Blasters Mojo */ readonly DualPhotonBlastersMojo: WeaponType;
    /** 76 Anti Matter Missiles Mojo */ readonly AntiMatterMissilesMojo: WeaponType;
    /** 77 Phase Disruptor Cannon */ readonly PhaseDisruptorCannon: WeaponType;
    /** 78 Phase Disruptor Cannon Danimoth */ readonly PhaseDisruptorCannonDanimoth: WeaponType;
    /** 79 Pulse Cannon */ readonly PulseCannon: WeaponType;
    /** 80 STS Photon Cannon */ readonly STSPhotonCannon: WeaponType;
    /** 81 STA Photon Cannon */ readonly STAPhotonCannon: WeaponType;
    /** 82 Scarab */ readonly Scarab: WeaponType;
    /** 83 Stasis Field */ readonly StasisField: WeaponType;
    /** 84 Psionic Storm */ readonly PsionicStorm: WeaponType;
    /** 85 Warp Blades Zeratul */ readonly WarpBladesZeratul: WeaponType;
    /** 86 Warp Blades Hero */ readonly WarpBladesHero: WeaponType;
    /** 87 unknown entry 87 */ readonly UnknownEntry87: WeaponType;
    /** 88 unknown entry 88 */ readonly UnknownEntry88: WeaponType;
    /** 89 unknown entry 89 */ readonly UnknownEntry89: WeaponType;
    /** 90 unknown entry 90 */ readonly UnknownEntry90: WeaponType;
    /** 91 unknown entry 91 */ readonly UnknownEntry91: WeaponType;
    /** 92 Platform Laser Battery */ readonly PlatformLaserBattery: WeaponType;
    /** 93 Independant Laser Battery */ readonly IndependantLaserBattery: WeaponType;
    /** 94 unknown entry 94 */ readonly UnknownEntry94: WeaponType;
    /** 95 unknown entry 95 */ readonly UnknownEntry95: WeaponType;
    /** 96 Twin Autocannons Floor Trap */ readonly TwinAutocannonsFloorTrap: WeaponType;
    /** 97 Hellfire Missile Pack Wall Trap */ readonly HellfireMissilePackWallTrap: WeaponType;
    /** 98 Flame Thrower Wall Trap */ readonly FlameThrowerWallTrap: WeaponType;
    /** 99 Hellfire Missile Pack Floor Trap */ readonly HellfireMissilePackFloorTrap: WeaponType;
    /** 100 Neutron Flare */ readonly NeutronFlare: WeaponType;
    /** 101 Disruption Web */ readonly DisruptionWeb: WeaponType;
    /** 102 Restoration */ readonly Restoration: WeaponType;
    /** 103 Halo Rockets */ readonly HaloRockets: WeaponType;
    /** 104 Corrosive Acid */ readonly CorrosiveAcid: WeaponType;
    /** 105 Mind Control */ readonly MindControl: WeaponType;
    /** 106 Feedback */ readonly Feedback: WeaponType;
    /** 107 Optical Flare */ readonly OpticalFlare: WeaponType;
    /** 108 Maelstrom */ readonly Maelstrom: WeaponType;
    /** 109 Subterranean Spines */ readonly SubterraneanSpines: WeaponType;
    /** 110 Warp Blades */ readonly WarpBlades: WeaponType;
    /** 111 C 10 Canister Rifle Samir Duran */ readonly C10CanisterRifleSamirDuran: WeaponType;
    /** 112 C 10 Canister Rifle Infested Duran */ readonly C10CanisterRifleInfestedDuran: WeaponType;
    /** 113 Dual Photon Blasters Artanis */ readonly DualPhotonBlastersArtanis: WeaponType;
    /** 114 Anti Matter Missiles Artanis */ readonly AntiMatterMissilesArtanis: WeaponType;
    /** 115 C 10 Canister Rifle Alexei Stukov */ readonly C10CanisterRifleAlexeiStukov: WeaponType;
    /** 116 unknown entry 116 */ readonly UnknownEntry116: WeaponType;
    /** 117 unknown entry 117 */ readonly UnknownEntry117: WeaponType;
    /** 118 unknown entry 118 */ readonly UnknownEntry118: WeaponType;
    /** 119 unknown entry 119 */ readonly UnknownEntry119: WeaponType;
    /** 120 unknown entry 120 */ readonly UnknownEntry120: WeaponType;
    /** 121 unknown entry 121 */ readonly UnknownEntry121: WeaponType;
    /** 122 unknown entry 122 */ readonly UnknownEntry122: WeaponType;
    /** 123 unknown entry 123 */ readonly UnknownEntry123: WeaponType;
    /** 124 unknown entry 124 */ readonly UnknownEntry124: WeaponType;
    /** 125 unknown entry 125 */ readonly UnknownEntry125: WeaponType;
    /** 126 unknown entry 126 */ readonly UnknownEntry126: WeaponType;
    /** 127 unknown entry 127 */ readonly UnknownEntry127: WeaponType;
    /** 128 unknown entry 128 */ readonly UnknownEntry128: WeaponType;
    /** 129 unknown entry 129 */ readonly UnknownEntry129: WeaponType;
}

/**
 * The weapon types.
 */
declare const WeaponType: WeaponTypeTable;

/**
 * The upgrade types of upgrades.dat, by the identifiers of their original names.
 */
interface UpgradeTypeTable extends ConstantTable<UpgradeType> {
    // GENERATED UpgradeType
    /** 0 Terran Infantry Armor */ readonly TerranInfantryArmor: UpgradeType;
    /** 1 Terran Vehicle Plating */ readonly TerranVehiclePlating: UpgradeType;
    /** 2 Terran Ship Plating */ readonly TerranShipPlating: UpgradeType;
    /** 3 Zerg Carapace */ readonly ZergCarapace: UpgradeType;
    /** 4 Zerg Flyer Carapace */ readonly ZergFlyerCarapace: UpgradeType;
    /** 5 Protoss Ground Armor */ readonly ProtossGroundArmor: UpgradeType;
    /** 6 Protoss Air Armor */ readonly ProtossAirArmor: UpgradeType;
    /** 7 Terran Infantry Weapons */ readonly TerranInfantryWeapons: UpgradeType;
    /** 8 Terran Vehicle Weapons */ readonly TerranVehicleWeapons: UpgradeType;
    /** 9 Terran Ship Weapons */ readonly TerranShipWeapons: UpgradeType;
    /** 10 Zerg Melee Attacks */ readonly ZergMeleeAttacks: UpgradeType;
    /** 11 Zerg Missile Attacks */ readonly ZergMissileAttacks: UpgradeType;
    /** 12 Zerg Flyer Attacks */ readonly ZergFlyerAttacks: UpgradeType;
    /** 13 Protoss Ground Weapons */ readonly ProtossGroundWeapons: UpgradeType;
    /** 14 Protoss Air Weapons */ readonly ProtossAirWeapons: UpgradeType;
    /** 15 Protoss Plasma Shields */ readonly ProtossPlasmaShields: UpgradeType;
    /** 16 U 238 Shells */ readonly U238Shells: UpgradeType;
    /** 17 Ion Thrusters */ readonly IonThrusters: UpgradeType;
    /** 18 unknown entry 18 */ readonly UnknownEntry18: UpgradeType;
    /** 19 Titan Reactor */ readonly TitanReactor: UpgradeType;
    /** 20 Ocular Implants */ readonly OcularImplants: UpgradeType;
    /** 21 Moebius Reactor */ readonly MoebiusReactor: UpgradeType;
    /** 22 Apollo Reactor */ readonly ApolloReactor: UpgradeType;
    /** 23 Colossus Reactor */ readonly ColossusReactor: UpgradeType;
    /** 24 Ventral Sacs */ readonly VentralSacs: UpgradeType;
    /** 25 Antennae */ readonly Antennae: UpgradeType;
    /** 26 Pneumatized Carapace */ readonly PneumatizedCarapace: UpgradeType;
    /** 27 Metabolic Boost */ readonly MetabolicBoost: UpgradeType;
    /** 28 Adrenal Glands */ readonly AdrenalGlands: UpgradeType;
    /** 29 Muscular Augments */ readonly MuscularAugments: UpgradeType;
    /** 30 Grooved Spines */ readonly GroovedSpines: UpgradeType;
    /** 31 Gamete Meiosis */ readonly GameteMeiosis: UpgradeType;
    /** 32 Metasynaptic Node */ readonly MetasynapticNode: UpgradeType;
    /** 33 Singularity Charge */ readonly SingularityCharge: UpgradeType;
    /** 34 Leg Enhancements */ readonly LegEnhancements: UpgradeType;
    /** 35 Scarab Damage */ readonly ScarabDamage: UpgradeType;
    /** 36 Reaver Capacity */ readonly ReaverCapacity: UpgradeType;
    /** 37 Gravitic Drive */ readonly GraviticDrive: UpgradeType;
    /** 38 Sensor Array */ readonly SensorArray: UpgradeType;
    /** 39 Gravitic Boosters */ readonly GraviticBoosters: UpgradeType;
    /** 40 Khaydarin Amulet */ readonly KhaydarinAmulet: UpgradeType;
    /** 41 Apial Sensors */ readonly ApialSensors: UpgradeType;
    /** 42 Gravitic Thrusters */ readonly GraviticThrusters: UpgradeType;
    /** 43 Carrier Capacity */ readonly CarrierCapacity: UpgradeType;
    /** 44 Khaydarin Core */ readonly KhaydarinCore: UpgradeType;
    /** 45 unknown entry 45 */ readonly UnknownEntry45: UpgradeType;
    /** 46 unknown entry 46 */ readonly UnknownEntry46: UpgradeType;
    /** 47 Argus Jewel */ readonly ArgusJewel: UpgradeType;
    /** 48 unknown entry 48 */ readonly UnknownEntry48: UpgradeType;
    /** 49 Argus Talisman */ readonly ArgusTalisman: UpgradeType;
    /** 50 unknown entry 50 */ readonly UnknownEntry50: UpgradeType;
    /** 51 Caduceus Reactor */ readonly CaduceusReactor: UpgradeType;
    /** 52 Chitinous Plating */ readonly ChitinousPlating: UpgradeType;
    /** 53 Anabolic Synthesis */ readonly AnabolicSynthesis: UpgradeType;
    /** 54 Charon Boosters */ readonly CharonBoosters: UpgradeType;
    /** 55 Unused 55 */ readonly Unused55: UpgradeType;
    /** 56 Unused 56 */ readonly Unused56: UpgradeType;
    /** 57 Unused 57 */ readonly Unused57: UpgradeType;
    /** 58 Unused 58 */ readonly Unused58: UpgradeType;
    /** 59 Unused 59 */ readonly Unused59: UpgradeType;
    /** 60 Unused 60 */ readonly Unused60: UpgradeType;
}

/**
 * The upgrade types.
 */
declare const UpgradeType: UpgradeTypeTable;

/**
 * The tech types of techdata.dat, by the identifiers of their original names.
 */
interface TechTypeTable extends ConstantTable<TechType> {
    // GENERATED TechType
    /** 0 Stim Packs */ readonly StimPacks: TechType;
    /** 1 Lockdown */ readonly Lockdown: TechType;
    /** 2 EMP Shockwave */ readonly EMPShockwave: TechType;
    /** 3 Spider Mines */ readonly SpiderMines: TechType;
    /** 4 Scanner Sweep */ readonly ScannerSweep: TechType;
    /** 5 Tank Siege Mode */ readonly TankSiegeMode: TechType;
    /** 6 Defensive Matrix */ readonly DefensiveMatrix: TechType;
    /** 7 Irradiate */ readonly Irradiate: TechType;
    /** 8 Yamato Gun */ readonly YamatoGun: TechType;
    /** 9 Cloaking Field */ readonly CloakingField: TechType;
    /** 10 Personnel Cloaking */ readonly PersonnelCloaking: TechType;
    /** 11 Burrowing */ readonly Burrowing: TechType;
    /** 12 Infestation */ readonly Infestation: TechType;
    /** 13 Spawn Broodlings */ readonly SpawnBroodlings: TechType;
    /** 14 Dark Swarm */ readonly DarkSwarm: TechType;
    /** 15 Plague */ readonly Plague: TechType;
    /** 16 Consume */ readonly Consume: TechType;
    /** 17 Ensnare */ readonly Ensnare: TechType;
    /** 18 Parasite */ readonly Parasite: TechType;
    /** 19 Psionic Storm */ readonly PsionicStorm: TechType;
    /** 20 Hallucination */ readonly Hallucination: TechType;
    /** 21 Recall */ readonly Recall: TechType;
    /** 22 Stasis Field */ readonly StasisField: TechType;
    /** 23 Archon Warp */ readonly ArchonWarp: TechType;
    /** 24 Restoration */ readonly Restoration: TechType;
    /** 25 Disruption Web */ readonly DisruptionWeb: TechType;
    /** 26 Unused 26 */ readonly Unused26: TechType;
    /** 27 Mind Control */ readonly MindControl: TechType;
    /** 28 Dark Archon Meld */ readonly DarkArchonMeld: TechType;
    /** 29 Feedback */ readonly Feedback: TechType;
    /** 30 Optical Flare */ readonly OpticalFlare: TechType;
    /** 31 Maelstrom */ readonly Maelstrom: TechType;
    /** 32 Lurker Aspect */ readonly LurkerAspect: TechType;
    /** 33 Unused 33 */ readonly Unused33: TechType;
    /** 34 Healing */ readonly Healing: TechType;
    /** 35 Unused 35 */ readonly Unused35: TechType;
    /** 36 Unused 36 */ readonly Unused36: TechType;
    /** 37 Unused 37 */ readonly Unused37: TechType;
    /** 38 Unused 38 */ readonly Unused38: TechType;
    /** 39 Unused 39 */ readonly Unused39: TechType;
    /** 40 Unused 40 */ readonly Unused40: TechType;
    /** 41 Unused 41 */ readonly Unused41: TechType;
    /** 42 Unused 42 */ readonly Unused42: TechType;
    /** 43 Unused 43 */ readonly Unused43: TechType;
}

/**
 * The tech types.
 */
declare const TechType: TechTypeTable;

/**
 * The orders of orders.dat, by the identifiers of their original names.
 */
interface OrderTable extends ConstantTable<Order> {
    // GENERATED Order
    /** 0 Die */ readonly Die: Order;
    /** 1 Stop */ readonly Stop: Order;
    /** 2 Guard */ readonly Guard: Order;
    /** 3 PlayerGuard */ readonly PlayerGuard: Order;
    /** 4 TurretGuard */ readonly TurretGuard: Order;
    /** 5 BunkerGuard */ readonly BunkerGuard: Order;
    /** 6 Move */ readonly Move: Order;
    /** 7 ReaverStop */ readonly ReaverStop: Order;
    /** 8 AttackDefault */ readonly AttackDefault: Order;
    /** 9 MoveToAttack */ readonly MoveToAttack: Order;
    /** 10 AttackUnit */ readonly AttackUnit: Order;
    /** 11 AttackFixedRange */ readonly AttackFixedRange: Order;
    /** 12 AttackTile */ readonly AttackTile: Order;
    /** 13 Hover */ readonly Hover: Order;
    /** 14 AttackMove */ readonly AttackMove: Order;
    /** 15 InfestedCommandCenter */ readonly InfestedCommandCenter: Order;
    /** 16 UnusedNothing */ readonly UnusedNothing: Order;
    /** 17 UnusedPowerup */ readonly UnusedPowerup: Order;
    /** 18 TowerGuard */ readonly TowerGuard: Order;
    /** 19 TowerAttack */ readonly TowerAttack: Order;
    /** 20 SpiderMine */ readonly SpiderMine: Order;
    /** 21 StayInRange */ readonly StayInRange: Order;
    /** 22 TurretAttack */ readonly TurretAttack: Order;
    /** 23 Nothing */ readonly Nothing: Order;
    /** 24 NothingWait */ readonly NothingWait: Order;
    /** 25 DroneStartBuild */ readonly DroneStartBuild: Order;
    /** 26 DroneBuild */ readonly DroneBuild: Order;
    /** 27 CastInfestation */ readonly CastInfestation: Order;
    /** 28 MoveToInfest */ readonly MoveToInfest: Order;
    /** 29 InfestingCommandCenter */ readonly InfestingCommandCenter: Order;
    /** 30 PlaceBuilding */ readonly PlaceBuilding: Order;
    /** 31 PlaceProtossBuilding */ readonly PlaceProtossBuilding: Order;
    /** 32 CreateProtossBuilding */ readonly CreateProtossBuilding: Order;
    /** 33 ConstructingBuilding */ readonly ConstructingBuilding: Order;
    /** 34 Repair */ readonly Repair: Order;
    /** 35 MoveToRepair */ readonly MoveToRepair: Order;
    /** 36 PlaceAddon */ readonly PlaceAddon: Order;
    /** 37 BuildAddon */ readonly BuildAddon: Order;
    /** 38 Train */ readonly Train: Order;
    /** 39 RallyPointUnit */ readonly RallyPointUnit: Order;
    /** 40 RallyPointTile */ readonly RallyPointTile: Order;
    /** 41 ZergBirth */ readonly ZergBirth: Order;
    /** 42 ZergUnitMorph */ readonly ZergUnitMorph: Order;
    /** 43 ZergBuildingMorph */ readonly ZergBuildingMorph: Order;
    /** 44 IncompleteBuilding */ readonly IncompleteBuilding: Order;
    /** 45 IncompleteMorphing */ readonly IncompleteMorphing: Order;
    /** 46 BuildNydusExit */ readonly BuildNydusExit: Order;
    /** 47 EnterNydusCanal */ readonly EnterNydusCanal: Order;
    /** 48 IncompleteWarping */ readonly IncompleteWarping: Order;
    /** 49 Follow */ readonly Follow: Order;
    /** 50 Carrier */ readonly Carrier: Order;
    /** 51 ReaverCarrierMove */ readonly ReaverCarrierMove: Order;
    /** 52 CarrierStop */ readonly CarrierStop: Order;
    /** 53 CarrierAttack */ readonly CarrierAttack: Order;
    /** 54 CarrierMoveToAttack */ readonly CarrierMoveToAttack: Order;
    /** 55 CarrierIgnore2 */ readonly CarrierIgnore2: Order;
    /** 56 CarrierFight */ readonly CarrierFight: Order;
    /** 57 CarrierHoldPosition */ readonly CarrierHoldPosition: Order;
    /** 58 Reaver */ readonly Reaver: Order;
    /** 59 ReaverAttack */ readonly ReaverAttack: Order;
    /** 60 ReaverMoveToAttack */ readonly ReaverMoveToAttack: Order;
    /** 61 ReaverFight */ readonly ReaverFight: Order;
    /** 62 ReaverHoldPosition */ readonly ReaverHoldPosition: Order;
    /** 63 TrainFighter */ readonly TrainFighter: Order;
    /** 64 InterceptorAttack */ readonly InterceptorAttack: Order;
    /** 65 ScarabAttack */ readonly ScarabAttack: Order;
    /** 66 RechargeShieldsUnit */ readonly RechargeShieldsUnit: Order;
    /** 67 RechargeShieldsBattery */ readonly RechargeShieldsBattery: Order;
    /** 68 ShieldBattery */ readonly ShieldBattery: Order;
    /** 69 InterceptorReturn */ readonly InterceptorReturn: Order;
    /** 70 DroneLand */ readonly DroneLand: Order;
    /** 71 BuildingLand */ readonly BuildingLand: Order;
    /** 72 BuildingLiftoff */ readonly BuildingLiftoff: Order;
    /** 73 DroneLiftOff */ readonly DroneLiftOff: Order;
    /** 74 LiftingOff */ readonly LiftingOff: Order;
    /** 75 ResearchTech */ readonly ResearchTech: Order;
    /** 76 Upgrade */ readonly Upgrade: Order;
    /** 77 Larva */ readonly Larva: Order;
    /** 78 SpawningLarva */ readonly SpawningLarva: Order;
    /** 79 Harvest1 */ readonly Harvest1: Order;
    /** 80 Harvest2 */ readonly Harvest2: Order;
    /** 81 MoveToGas */ readonly MoveToGas: Order;
    /** 82 WaitForGas */ readonly WaitForGas: Order;
    /** 83 HarvestGas */ readonly HarvestGas: Order;
    /** 84 ReturnGas */ readonly ReturnGas: Order;
    /** 85 MoveToMinerals */ readonly MoveToMinerals: Order;
    /** 86 WaitForMinerals */ readonly WaitForMinerals: Order;
    /** 87 MiningMinerals */ readonly MiningMinerals: Order;
    /** 88 GatheringInterrupted */ readonly GatheringInterrupted: Order;
    /** 89 GatherWaitInterrupted */ readonly GatherWaitInterrupted: Order;
    /** 90 ReturnMinerals */ readonly ReturnMinerals: Order;
    /** 91 RechargeShieldsUnitRemoveOverlay */ readonly RechargeShieldsUnitRemoveOverlay: Order;
    /** 92 EnterTransport */ readonly EnterTransport: Order;
    /** 93 PickupIdle */ readonly PickupIdle: Order;
    /** 94 PickupTransport */ readonly PickupTransport: Order;
    /** 95 PickupBunker */ readonly PickupBunker: Order;
    /** 96 Pickup4 */ readonly Pickup4: Order;
    /** 97 PowerupIdle */ readonly PowerupIdle: Order;
    /** 98 Sieging */ readonly Sieging: Order;
    /** 99 Unsieging */ readonly Unsieging: Order;
    /** 100 WatchTarget */ readonly WatchTarget: Order;
    /** 101 InitCreepGrowth */ readonly InitCreepGrowth: Order;
    /** 102 SpreadCreep */ readonly SpreadCreep: Order;
    /** 103 StoppingCreepGrowth */ readonly StoppingCreepGrowth: Order;
    /** 104 GuardianAspect */ readonly GuardianAspect: Order;
    /** 105 ArchonWarp */ readonly ArchonWarp: Order;
    /** 106 CompletingArchonSummon */ readonly CompletingArchonSummon: Order;
    /** 107 HoldPosition */ readonly HoldPosition: Order;
    /** 108 QueenHoldPosition */ readonly QueenHoldPosition: Order;
    /** 109 Cloak */ readonly Cloak: Order;
    /** 110 Decloak */ readonly Decloak: Order;
    /** 111 Unload */ readonly Unload: Order;
    /** 112 MoveUnload */ readonly MoveUnload: Order;
    /** 113 FireYamatoGun */ readonly FireYamatoGun: Order;
    /** 114 MoveToFireYamatoGun */ readonly MoveToFireYamatoGun: Order;
    /** 115 CastLockdown */ readonly CastLockdown: Order;
    /** 116 Burrowing */ readonly Burrowing: Order;
    /** 117 Burrowed */ readonly Burrowed: Order;
    /** 118 Unburrowing */ readonly Unburrowing: Order;
    /** 119 CastDarkSwarm */ readonly CastDarkSwarm: Order;
    /** 120 CastParasite */ readonly CastParasite: Order;
    /** 121 CastSpawnBroodlings */ readonly CastSpawnBroodlings: Order;
    /** 122 CastEMPShockwave */ readonly CastEMPShockwave: Order;
    /** 123 NukeWait */ readonly NukeWait: Order;
    /** 124 NukeTrain */ readonly NukeTrain: Order;
    /** 125 NukeLaunch */ readonly NukeLaunch: Order;
    /** 126 NukePaint */ readonly NukePaint: Order;
    /** 127 NukeUnit */ readonly NukeUnit: Order;
    /** 128 CastNuclearStrike */ readonly CastNuclearStrike: Order;
    /** 129 NukeTrack */ readonly NukeTrack: Order;
    /** 130 InitializeArbiter */ readonly InitializeArbiter: Order;
    /** 131 CloakNearbyUnits */ readonly CloakNearbyUnits: Order;
    /** 132 PlaceMine */ readonly PlaceMine: Order;
    /** 133 RightClickAction */ readonly RightClickAction: Order;
    /** 134 SuicideUnit */ readonly SuicideUnit: Order;
    /** 135 SuicideLocation */ readonly SuicideLocation: Order;
    /** 136 SuicideHoldPosition */ readonly SuicideHoldPosition: Order;
    /** 137 CastRecall */ readonly CastRecall: Order;
    /** 138 Teleport */ readonly Teleport: Order;
    /** 139 CastScannerSweep */ readonly CastScannerSweep: Order;
    /** 140 Scanner */ readonly Scanner: Order;
    /** 141 CastDefensiveMatrix */ readonly CastDefensiveMatrix: Order;
    /** 142 CastPsionicStorm */ readonly CastPsionicStorm: Order;
    /** 143 CastIrradiate */ readonly CastIrradiate: Order;
    /** 144 CastPlague */ readonly CastPlague: Order;
    /** 145 CastConsume */ readonly CastConsume: Order;
    /** 146 CastEnsnare */ readonly CastEnsnare: Order;
    /** 147 CastStasisField */ readonly CastStasisField: Order;
    /** 148 CastHallucination */ readonly CastHallucination: Order;
    /** 149 Hallucination2 */ readonly Hallucination2: Order;
    /** 150 ResetCollision */ readonly ResetCollision: Order;
    /** 151 ResetHarvestCollision */ readonly ResetHarvestCollision: Order;
    /** 152 Patrol */ readonly Patrol: Order;
    /** 153 CTFCOPInit */ readonly CTFCOPInit: Order;
    /** 154 CTFCOPStarted */ readonly CTFCOPStarted: Order;
    /** 155 CTFCOP2 */ readonly CTFCOP2: Order;
    /** 156 ComputerAI */ readonly ComputerAI: Order;
    /** 157 AtkMoveEP */ readonly AtkMoveEP: Order;
    /** 158 HarassMove */ readonly HarassMove: Order;
    /** 159 AIPatrol */ readonly AIPatrol: Order;
    /** 160 GuardPost */ readonly GuardPost: Order;
    /** 161 RescuePassive */ readonly RescuePassive: Order;
    /** 162 Neutral */ readonly Neutral: Order;
    /** 163 ComputerReturn */ readonly ComputerReturn: Order;
    /** 164 InitializePsiProvider */ readonly InitializePsiProvider: Order;
    /** 165 SelfDestructing */ readonly SelfDestructing: Order;
    /** 166 Critter */ readonly Critter: Order;
    /** 167 HiddenGun */ readonly HiddenGun: Order;
    /** 168 OpenDoor */ readonly OpenDoor: Order;
    /** 169 CloseDoor */ readonly CloseDoor: Order;
    /** 170 HideTrap */ readonly HideTrap: Order;
    /** 171 RevealTrap */ readonly RevealTrap: Order;
    /** 172 EnableDoodad */ readonly EnableDoodad: Order;
    /** 173 DisableDoodad */ readonly DisableDoodad: Order;
    /** 174 WarpIn */ readonly WarpIn: Order;
    /** 175 MedicIdle */ readonly MedicIdle: Order;
    /** 176 MedicHeal */ readonly MedicHeal: Order;
    /** 177 HealMove */ readonly HealMove: Order;
    /** 178 MedicHoldPosition */ readonly MedicHoldPosition: Order;
    /** 179 MedicHealToIdle */ readonly MedicHealToIdle: Order;
    /** 180 CastRestoration */ readonly CastRestoration: Order;
    /** 181 CastDisruptionWeb */ readonly CastDisruptionWeb: Order;
    /** 182 CastMindControl */ readonly CastMindControl: Order;
    /** 183 DarkArchonMeld */ readonly DarkArchonMeld: Order;
    /** 184 CastFeedback */ readonly CastFeedback: Order;
    /** 185 CastOpticalFlare */ readonly CastOpticalFlare: Order;
    /** 186 CastMaelstrom */ readonly CastMaelstrom: Order;
    /** 187 JunkYardDog */ readonly JunkYardDog: Order;
    /** 188 Fatal */ readonly Fatal: Order;
}

/**
 * The orders.
 */
declare const Order: OrderTable;
