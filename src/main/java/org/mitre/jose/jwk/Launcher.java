package org.mitre.jose.jwk;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.KeyUse;

/**
 * Small Helper App to generate Json Web Keys
 */
public class Launcher {

    private static Options options;

    private static List<Curve> ecCurves = Arrays.asList(
    	Curve.P_256, Curve.SECP256K1, Curve.P_384, Curve.P_521);

    private static List<Curve> okpCurves = Arrays.asList(
    	Curve.Ed25519, Curve.Ed448, Curve.X25519, Curve.X448);

    public static void main(String[] args) {

    	Security.addProvider(new BouncyCastleProvider());

        options = new Options();

        options.addOption("t", true, "Key Type, one of: " + KeyType.RSA.getValue() + ", " + KeyType.OCT.getValue() + ", " +
                KeyType.EC.getValue() + ", " + KeyType.OKP.getValue());
        options.addOption("s", true, "Key Size in bits, required for RSA and oct key types. Must be an integer divisible by 8");
        options.addOption("u", true, "Usage, one of: enc, sig (optional)");
        options.addOption("a", true, "Algorithm (optional)");
        options.addOption("i", true, "Key ID (optional), one will be generated if not defined");
        options.addOption("I", false, "Don't generate a Key ID if none defined");
        options.addOption("T", true,  "Key ID Type for generated kid claim, one of: " + KeyIdType.UsageTimestamp + ", " + KeyIdType.Sha1PubKey + " or " + KeyIdType.None);
        options.addOption("p", false, "Display public key separately");
        options.addOption("c", true, "Key Curve, required for EC or OKP key type. Must be one of " + Joiner.on(", ").join(ecCurves)
        	+ " for EC keys or one of " + Joiner.on(", ").join(okpCurves) + " for OKP keys.");
        options.addOption("S", false, "Wrap the generated key in a KeySet");
        options.addOption("o", true, "Write output to file (will append to existing KeySet if -S is used), No Display of Key Material");

        CommandLineParser parser = new PosixParser();
        try {
            CommandLine cmd = parser.parse(options, args);

            String kty = cmd.getOptionValue("t");
            String size = cmd.getOptionValue("s");
            String use = cmd.getOptionValue("u");
            String alg = cmd.getOptionValue("a");
            String kid = cmd.getOptionValue("i");
            String crv = cmd.getOptionValue("c");
            boolean keySet = cmd.hasOption("S");
            boolean pubKey = cmd.hasOption("p");
            boolean doNotGenerateKid = cmd.hasOption("I");
            String outFile = cmd.getOptionValue("o");
            KeyIdGenerator keyIdGenerator = getKeyIdGenerator(cmd.getOptionValue("T"), doNotGenerateKid, kid);
            
            // check for required fields
            if (kty == null) {
                printUsageAndExit("Key type must be supplied.");
            }

            // parse out the important bits

            KeyType keyType = KeyType.parse(kty);

            KeyUse keyUse = null;
            if (use != null) {
                if (use.equals("sig")) {
                    keyUse = KeyUse.SIGNATURE;
                } else if (use.equals("enc")) {
                    keyUse = KeyUse.ENCRYPTION;
                } else {
                    printUsageAndExit("Invalid key usage, must be 'sig' or 'enc', got " + use);
                }
            }

            Algorithm keyAlg = null;
            if (!Strings.isNullOrEmpty(alg)) {
                keyAlg = JWSAlgorithm.parse(alg);
            }

            JWK jwk = null;

            if (keyType.equals(KeyType.RSA)) {
                // surrounding try/catch catches numberformatexception from this
                if (Strings.isNullOrEmpty(size)) {
                    printUsageAndExit("Key size (in bits) is required for key type " + keyType);
                }

                Integer keySize = Integer.decode(size);
                if (keySize % 8 != 0) {
                    printUsageAndExit("Key size (in bits) must be divisible by 8, got " + keySize);
                }

                jwk = RSAKeyMaker.make(keySize, keyUse, keyAlg, keyIdGenerator);
            } else if (keyType.equals(KeyType.OCT)) {
                // surrounding try/catch catches numberformatexception from this
                if (Strings.isNullOrEmpty(size)) {
                    printUsageAndExit("Key size (in bits) is required for key type " + keyType);
                }
                Integer keySize = Integer.decode(size);
                if (keySize % 8 != 0) {
                    printUsageAndExit("Key size (in bits) must be divisible by 8, got " + keySize);
                }

                jwk = OctetSequenceKeyMaker.make(keySize, keyUse, keyAlg, keyIdGenerator);
            } else if (keyType.equals(KeyType.EC)) {
                if (Strings.isNullOrEmpty(crv)) {
                    printUsageAndExit("Curve is required for key type " + keyType);
                }
                Curve keyCurve = Curve.parse(crv);

                if (!ecCurves.contains(keyCurve)) {
                	printUsageAndExit("Curve " + crv + " is not valid for key type " + keyType);
                }

                jwk = ECKeyMaker.make(keyCurve, keyUse, keyAlg, keyIdGenerator);
            } else if (keyType.equals(KeyType.OKP)) {
                if (Strings.isNullOrEmpty(crv)) {
                    printUsageAndExit("Curve is required for key type " + keyType);
                }
                Curve keyCurve = Curve.parse(crv);

                if (!okpCurves.contains(keyCurve)) {
                	printUsageAndExit("Curve " + crv + " is not valid for key type " + keyType);
                }

                jwk = OKPKeyMaker.make(keyCurve, keyUse, keyAlg, keyIdGenerator);
            } else {
                printUsageAndExit("Unknown key type: " + keyType);
            }

            // round trip it through GSON to get a prettyprinter
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            if (outFile == null) {

                System.out.println("Full key:");

                printKey(keySet, jwk, gson);

                if (pubKey) {
                    System.out.println(); // spacer

                    // also print public key, if possible
                    JWK pub = jwk.toPublicJWK();

                    if (pub != null) {
                        System.out.println("Public key:");
                        printKey(keySet, pub, gson);
                    } else {
                        System.out.println("No public key.");
                    }
                }
            } else {
                writeKeyToFile(keySet, outFile, jwk, gson);
            }

        } catch (NumberFormatException e) {
            printUsageAndExit("Invalid key size: " + e.getMessage());
        } catch (ParseException e) {
            printUsageAndExit("Failed to parse arguments: " + e.getMessage());
        } catch (java.text.ParseException e) {
            printUsageAndExit("Could not parse existing KeySet: " + e.getMessage());
        } catch (IOException e) {
            printUsageAndExit("Could not read existing KeySet: " + e.getMessage());
        }
    }

    private static KeyIdGenerator getKeyIdGenerator(String optionValue, boolean doNotGenerateKid, final String kid) {
      int i = 0;
      
      if(optionValue != null)
        i++;
      
      if(doNotGenerateKid)
        i++;
      
      if(kid != null)
        i++;
      
      if(i>1)
        printUsageAndExit("Only one of -I -i and -T may be specified.");
      
      if(kid != null) {
        return (keyUse, pubKey) -> kid;
      }
      
      if(doNotGenerateKid)
        return KeyIdType.None;
      
      try
      {
        return KeyIdType.valueOf(optionValue);
      }
      catch(NullPointerException | IllegalArgumentException e)
      {
        return KeyIdType.UsageTimestamp;
      }
    }

    private static void writeKeyToFile(boolean keySet, String outFile, JWK jwk, Gson gson) throws IOException,
            java.text.ParseException {
        JsonElement json;
        File output = new File(outFile);
        if (keySet) {
            List<JWK> existingKeys = output.exists() ? JWKSet.load(output).getKeys() : Collections.<JWK>emptyList();
            List<JWK> jwkList = new ArrayList<>(existingKeys);
            jwkList.add(jwk);
            JWKSet jwkSet = new JWKSet(jwkList);
            json = new JsonParser().parse(jwkSet.toJSONObject(false).toJSONString());
        } else {
            json = new JsonParser().parse(jwk.toJSONString());
        }
        Writer os = null;
        try {
            os = new BufferedWriter(new FileWriter(output));
            os.write(gson.toJson(json));
        } finally {
            os.close();
        }
    }

    private static void printKey(boolean keySet, JWK jwk, Gson gson) {
        if (keySet) {
            JWKSet jwkSet = new JWKSet(jwk);
            JsonElement json = new JsonParser().parse(jwkSet.toJSONObject(false).toJSONString());
            System.out.println(gson.toJson(json));
        } else {
            JsonElement json = new JsonParser().parse(jwk.toJSONString());
            System.out.println(gson.toJson(json));
        }
    }

    // print out a usage message and quit
    private static void printUsageAndExit(String message) {
        if (message != null) {
            System.err.println(message);
        }

        List<String> optionOrder = ImmutableList.of("t", "s", "c", "u", "a", "i", "I", "p", "S", "o");

        HelpFormatter formatter = new HelpFormatter();
        formatter.setOptionComparator((o1, o2) -> optionOrder.indexOf(((Option)o1).getOpt()) - optionOrder.indexOf(((Option)o2).getOpt()));
        formatter.printHelp("java -jar json-web-key-generator.jar -t <keyType> [options]", options);

        // kill the program
        System.exit(1);
    }
}
