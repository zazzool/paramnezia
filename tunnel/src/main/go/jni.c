/* SPDX-License-Identifier: Apache-2.0
 *
 * JNI bindings for io.paramnezia.tunnel.AwgNative. Derived from amneziawg-android's jni.c
 * (Copyright © 2017-2021 Jason A. Donenfeld).
 */

#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

struct go_string { const char *str; long n; };
extern int pnTurnOn(struct go_string ifname, int tun_fd, struct go_string settings, struct go_string dns, int dns_direct_fd);
extern void pnSetDnsRules(struct go_string block, struct go_string direct, struct go_string allow);
extern long long pnDnsBlocked(void);
extern void pnTurnOff(int handle);
extern int pnGetSocketV4(int handle);
extern int pnGetSocketV6(int handle);
extern char *pnGetConfig(int handle);
extern int pnSetConfig(int handle, struct go_string settings);
extern char *pnVersion(void);
extern void pnSetVerbose(bool on);
extern int pnEd25519Generate(void *seed_out, void *pub_out);
extern void pnEd25519Sign(void *seed, void *msg, int msg_len, void *sig_out);
extern bool pnEd25519Verify(void *pub, void *msg, int msg_len, void *sig);

#define FN(name) Java_io_paramnezia_tunnel_AwgNative_##name

static struct go_string gs(JNIEnv *env, jstring s, const char **chars)
{
	*chars = (*env)->GetStringUTFChars(env, s, 0);
	return (struct go_string){ .str = *chars, .n = (*env)->GetStringUTFLength(env, s) };
}

static jstring take_string(JNIEnv *env, char *s)
{
	if (!s)
		return NULL;
	jstring ret = (*env)->NewStringUTF(env, s);
	free(s);
	return ret;
}

JNIEXPORT jint JNICALL FN(turnOn)(JNIEnv *env, jclass c, jstring ifname, jint tun_fd, jstring settings, jstring dns, jint dns_direct_fd)
{
	const char *a, *b, *d;
	struct go_string ga = gs(env, ifname, &a), gb = gs(env, settings, &b), gd = gs(env, dns, &d);
	int ret = pnTurnOn(ga, tun_fd, gb, gd, dns_direct_fd);
	(*env)->ReleaseStringUTFChars(env, ifname, a);
	(*env)->ReleaseStringUTFChars(env, settings, b);
	(*env)->ReleaseStringUTFChars(env, dns, d);
	return ret;
}

JNIEXPORT void JNICALL FN(setDnsRules)(JNIEnv *env, jclass c, jstring block, jstring direct, jstring allow)
{
	const char *a, *b, *d;
	struct go_string ga = gs(env, block, &a), gb = gs(env, direct, &b), gd = gs(env, allow, &d);
	pnSetDnsRules(ga, gb, gd);
	(*env)->ReleaseStringUTFChars(env, block, a);
	(*env)->ReleaseStringUTFChars(env, direct, b);
	(*env)->ReleaseStringUTFChars(env, allow, d);
}

JNIEXPORT jlong JNICALL FN(dnsBlocked)(JNIEnv *env, jclass c) { return pnDnsBlocked(); }

JNIEXPORT void JNICALL FN(turnOff)(JNIEnv *env, jclass c, jint handle) { pnTurnOff(handle); }
JNIEXPORT jint JNICALL FN(socketV4)(JNIEnv *env, jclass c, jint handle) { return pnGetSocketV4(handle); }
JNIEXPORT jint JNICALL FN(socketV6)(JNIEnv *env, jclass c, jint handle) { return pnGetSocketV6(handle); }
JNIEXPORT jstring JNICALL FN(getConfig)(JNIEnv *env, jclass c, jint handle) { return take_string(env, pnGetConfig(handle)); }
JNIEXPORT jstring JNICALL FN(version)(JNIEnv *env, jclass c) { return take_string(env, pnVersion()); }
JNIEXPORT void JNICALL FN(setVerbose)(JNIEnv *env, jclass c, jboolean on) { pnSetVerbose(on); }

JNIEXPORT jint JNICALL FN(setConfig)(JNIEnv *env, jclass c, jint handle, jstring settings)
{
	const char *a;
	struct go_string ga = gs(env, settings, &a);
	int ret = pnSetConfig(handle, ga);
	(*env)->ReleaseStringUTFChars(env, settings, a);
	return ret;
}

/* Returns seed (32 bytes) followed by public key (32 bytes). */
JNIEXPORT jbyteArray JNICALL FN(ed25519Generate)(JNIEnv *env, jclass c)
{
	unsigned char out[64];
	if (pnEd25519Generate(out, out + 32) != 0)
		return NULL;
	jbyteArray arr = (*env)->NewByteArray(env, 64);
	(*env)->SetByteArrayRegion(env, arr, 0, 64, (jbyte *)out);
	memset(out, 0, sizeof(out));
	return arr;
}

JNIEXPORT jbyteArray JNICALL FN(ed25519Sign)(JNIEnv *env, jclass c, jbyteArray seed, jbyteArray msg)
{
	if ((*env)->GetArrayLength(env, seed) != 32)
		return NULL;
	unsigned char sig[64];
	jbyte *s = (*env)->GetByteArrayElements(env, seed, 0);
	jbyte *m = (*env)->GetByteArrayElements(env, msg, 0);
	pnEd25519Sign(s, m, (*env)->GetArrayLength(env, msg), sig);
	(*env)->ReleaseByteArrayElements(env, seed, s, JNI_ABORT);
	(*env)->ReleaseByteArrayElements(env, msg, m, JNI_ABORT);
	jbyteArray arr = (*env)->NewByteArray(env, 64);
	(*env)->SetByteArrayRegion(env, arr, 0, 64, (jbyte *)sig);
	return arr;
}

JNIEXPORT jboolean JNICALL FN(ed25519Verify)(JNIEnv *env, jclass c, jbyteArray pub, jbyteArray msg, jbyteArray sig)
{
	if ((*env)->GetArrayLength(env, pub) != 32 || (*env)->GetArrayLength(env, sig) != 64)
		return JNI_FALSE;
	jbyte *p = (*env)->GetByteArrayElements(env, pub, 0);
	jbyte *m = (*env)->GetByteArrayElements(env, msg, 0);
	jbyte *s = (*env)->GetByteArrayElements(env, sig, 0);
	bool ok = pnEd25519Verify(p, m, (*env)->GetArrayLength(env, msg), s);
	(*env)->ReleaseByteArrayElements(env, pub, p, JNI_ABORT);
	(*env)->ReleaseByteArrayElements(env, msg, m, JNI_ABORT);
	(*env)->ReleaseByteArrayElements(env, sig, s, JNI_ABORT);
	return ok ? JNI_TRUE : JNI_FALSE;
}
