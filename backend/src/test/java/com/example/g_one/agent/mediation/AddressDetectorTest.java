package com.example.g_one.agent.mediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.LegRole;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which turns call G-One by name. Each language group has turns that are addressed (true) and turns that only mention
 * the name, or use a word that sounds like it, and must be relayed (false). A request routed through G-One ("ask G-One",
 * "G-One se pucho") is addressed: it is for G-One to handle, never for the other party. The separator is '|'.
 */
class AddressDetectorTest {
    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            G-One, how much crop is that farmer trying to sell?          | true
            G one what did the buyer offer                              | true
            Gee one, what is the quantity?                              | true
            Hey G1, repeat that please                                  | true
            OK G-One, is the deal confirmed?                            | true
            How much does he have, G-One?                               | true
            What is the mandi price G-One?                              | true
            Gone, what did he just say?                                 | true
            G-One?                                                      | true
            Thanks G-One, and what is left to agree?                    | true
            The G-One said it is fine                                   | false
            G-One said it is fine for me                                | false
            Ask G-One if you want                                       | true
            The price has gone up this week                             | false
            Gone are the days of low prices                             | false
            I can give twenty quintal at two thousand                   | false
            I told G-One about my onions                                | false
            gone                                                        | false
            """)
    void english(String turn, boolean addressed) {
        assertThat(AddressDetector.addressed(turn, LegRole.FARMER)).isEqualTo(addressed);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            G-One batao kitna maal hai                                  | true
            G one, buyer ne kitna bola?                                 | true
            Arre G-One, quantity kya hai?                               | true
            G1 bhai, dobara bolo                                        | true
            Ji one kitne quintal ki baat hui?                           | true
            Accha G-One, mandi ka rate kya hai?                         | true
            Haan, G-One, deal pakki hui kya?                            | true
            Kitna maal hai uske paas G-One?                             | true
            jee van, kya bacha hai tay karna?                           | true
            Suno G-One, summary batao                                   | true
            G-One ne bola tha theek hai                                 | false
            G-One se pucho                                              | true
            G-One ko bata do                                            | true
            Main bees quintal de sakta hoon                             | false
            Rate thoda badhao bhai                                      | false
            """)
    void hinglish(String turn, boolean addressed) {
        assertThat(AddressDetector.addressed(turn, LegRole.FARMER)).isEqualTo(addressed);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            जी-वन, किसान कितनी फसल बेच रहा है?                          | true
            जी वन बताओ खरीदार ने क्या कहा                                | true
            अरे जी-वन, मंडी का भाव क्या है?                               | true
            जी 1, दोबारा बोलिए                                           | true
            जीवन, क्या सौदा पक्का हुआ?                                    | true
            जीवन बताओ कितना माल है                                       | true
            हाँ जी-वन, अभी क्या तय होना बाकी है?                          | true
            कितना माल है उनके पास जी-वन?                                 | true
            सुनिए जी-वन, अब तक की बात बताइए                              | true
            जी-वन?                                                      | true
            जीवन में बहुत मेहनत है                                        | false
            मेरा जीवन खेती में बीता                                       | false
            जी-वन ने कहा था ठीक है                                        | false
            जी-वन से पूछ लीजिए                                           | true
            जी हाँ, बीस क्विंटल है                                        | false
            हाँ जी, वन विभाग के पास खेत है                                | false
            """)
    void hindi(String turn, boolean addressed) {
        assertThat(AddressDetector.addressed(turn, LegRole.FARMER)).isEqualTo(addressed);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            জি ওয়ান, কৃষক কত ফসল বিক্রি করছেন?                           | true
            জি-ওয়ান বলুন ক্রেতা কত দাম বলেছেন                             | true
            এই জি ওয়ান, মান্ডির দাম কত?                                   | true
            জি 1, আবার বলুন                                              | true
            G one, ki baki ache?                                         | true
            আচ্ছা জি ওয়ান, চুক্তি কি পাকা হয়েছে?                           | true
            কত মাল আছে ওনার কাছে, জি ওয়ান?                                | true
            জীবন, কত কুইন্টাল?                                            | true
            জি ওয়ান বলেছে ঠিক আছে                                         | false
            জি-ওয়ানের কাছে জিজ্ঞেস করুন                                   | true
            আমার জীবন চাষে কেটেছে                                          | false
            কুড়ি কুইন্টাল পেঁয়াজ আছে                                      | false
            """)
    void bengali(String turn, boolean addressed) {
        assertThat(AddressDetector.addressed(turn, LegRole.FARMER)).isEqualTo(addressed);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            ஜி ஒன், விவசாயி எவ்வளவு விற்கிறார்?                            | true
            ஜி-ஒன் சொல்லுங்க வாங்குபவர் என்ன சொன்னார்                       | true
            ஹலோ ஜி ஒன், மண்டி விலை என்ன?                                   | true
            ஜி 1, மீண்டும் சொல்லுங்கள்                                      | true
            சரி ஜி ஒன், ஒப்பந்தம் உறுதியா?                                   | true
            எத்தனை குவிண்டால் இருக்கு, ஜி ஒன்?                               | true
            ஜீ ஒன் என்ன பாக்கி இருக்கு?                                     | true
            G one, evvalavu quintal?                                      | true
            ஜி ஒன் சொன்னது சரி                                             | false
            ஜி ஒன் கிட்ட கேளுங்க                                           | true
            இருபது குவிண்டால் வெங்காயம் இருக்கு                              | false
            விலை கொஞ்சம் அதிகம்                                            | false
            """)
    void tamil(String turn, boolean addressed) {
        assertThat(AddressDetector.addressed(turn, LegRole.FARMER)).isEqualTo(addressed);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            ಜಿ ಒನ್, ರೈತ ಎಷ್ಟು ಬೆಳೆ ಮಾರುತ್ತಿದ್ದಾರೆ?                            | true
            ಜಿ-ಒನ್ ಹೇಳಿ ಖರೀದಿದಾರ ಏನು ಹೇಳಿದರು                                | true
            ಹಲೋ ಜಿ ಒನ್, ಮಂಡಿ ಬೆಲೆ ಏನು?                                     | true
            ಜಿ 1, ಮತ್ತೆ ಹೇಳಿ                                                | true
            ಸರಿ ಜಿ ಒನ್, ಒಪ್ಪಂದ ಖಚಿತವೇ?                                      | true
            ಎಷ್ಟು ಕ್ವಿಂಟಲ್ ಇದೆ, ಜಿ ಒನ್?                                       | true
            ಜೀ ಒನ್ ಏನು ಬಾಕಿ ಇದೆ?                                            | true
            G one, eshtu quintal?                                         | true
            ಜಿ ಒನ್ ಹೇಳಿದ್ದು ಸರಿ                                             | false
            ಜಿ ಒನ್ ಗೆ ಕೇಳಿ                                                  | true
            ಇಪ್ಪತ್ತು ಕ್ವಿಂಟಲ್ ಈರುಳ್ಳಿ ಇದೆ                                     | false
            ಬೆಲೆ ಸ್ವಲ್ಪ ಜಾಸ್ತಿ                                               | false
            """)
    void kannada(String turn, boolean addressed) {
        assertThat(AddressDetector.addressed(turn, LegRole.FARMER)).isEqualTo(addressed);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            G-One can tell you the price                                | false
            G-One will tell you later                                   | false
            G-One can you tell me the price?                            | true
            Please ask G-One what his price is                          | true
            Tell G-One to repeat the price                              | true
            जीवन क्या है?                                                | false
            जीवन क्या है                                                 | false
            जीवन, क्या है रेट?                                            | true
            gone how much                                               | false
            Maybe G-One knows his rate                                  | false
            """)
    void ambiguityPolicyForTheFarmer(String turn, boolean addressed) {
        assertThat(AddressDetector.addressed(turn, LegRole.FARMER)).isEqualTo(addressed);
    }

    /** A buyer turn that names G-One and is not reported speech is kept from the farmer: it may ask for private things. */
    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            Maybe G-One knows his rate                                  | true
            I really like G-One                                         | true
            What would G-One say about his lowest price                 | true
            Please ask G-One what his price is                          | true
            G-One, how much does he have?                               | true
            The G-One said it is fine                                   | false
            G-One said it is fine                                       | false
            G-One can tell you the price                                | false
            I told G-One about the price                                | false
            G-One ki baat sahi hai                                      | false
            I can pay four thousand per quintal                         | false
            जीवन क्या है?                                                | false
            """)
    void ambiguityPolicyForTheBuyer(String turn, boolean addressed) {
        assertThat(AddressDetector.addressed(turn, LegRole.BUYER)).isEqualTo(addressed);
    }
}
